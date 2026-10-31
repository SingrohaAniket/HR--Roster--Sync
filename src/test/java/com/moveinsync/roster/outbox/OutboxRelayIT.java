package com.moveinsync.roster.outbox;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.moveinsync.roster.config.OutboxProperties;
import com.moveinsync.roster.config.RabbitConfig;
import com.moveinsync.roster.ingest.AbstractIngestIT;
import com.moveinsync.roster.observability.RosterMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@ExtendWith(OutputCaptureExtension.class)
class OutboxRelayIT extends AbstractIngestIT {

    private static final Logger log = LoggerFactory.getLogger("CRASH-TEST");
    private static final int LEASE_SECONDS = 2;
    private static final int MAX_ATTEMPTS = 4;
    private static final int RABBIT_PORT = 5672;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management-alpine")
            .withExposedPorts(RABBIT_PORT)
            .withEnv("RABBITMQ_DEFAULT_USER", "roster")
            .withEnv("RABBITMQ_DEFAULT_PASS", "rosterpass")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(RABBIT_PORT));
        registry.add("spring.rabbitmq.username", () -> "roster");
        registry.add("spring.rabbitmq.password", () -> "rosterpass");
        registry.add("roster.outbox.lease-seconds", () -> LEASE_SECONDS);
        registry.add("roster.outbox.confirm-timeout-ms", () -> 1000);
        registry.add("roster.outbox.max-attempts", () -> MAX_ATTEMPTS);
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxStore store;

    @Autowired
    private RosterMetrics metrics;

    @MockitoSpyBean
    private OutboxPublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private Queue queue;

    @Autowired
    private TopicExchange exchange;

    @Autowired
    private Binding binding;

    @BeforeEach
    void startClean() {
        jdbc.update("UPDATE outbox SET status = 'PUBLISHED' WHERE status <> 'PUBLISHED'");
        rabbitAdmin.purgeQueue(RabbitConfig.QUEUE, false);
    }

    private Map<String, Object> row(String externalId) {
        return jdbc.queryForMap("SELECT id, status, attempts, next_attempt_at, published_at FROM outbox WHERE aggregate_id = ?", externalId);
    }

    private long rowId(String externalId) {
        return (Long) row(externalId).get("id");
    }

    private String ingestNewEmployee() throws Exception {
        String id = unique("RELAY");
        assertEquals("APPLIED", outcome(send(operator(), newJoiner(id)), 0));
        return id;
    }

    private void insertRows(String prefix, int count) {
        jdbc.batchUpdate("INSERT INTO outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at) VALUES (1, 'EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '{}', DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND))",
                java.util.stream.IntStream.range(0, count).mapToObj(i -> new Object[] {prefix + "-" + i}).toList());
    }

    private List<Message> drainQueue() {
        List<Message> messages = new ArrayList<>();
        for (Message m = rabbitTemplate.receive(RabbitConfig.QUEUE, 500); m != null; m = rabbitTemplate.receive(RabbitConfig.QUEUE, 300)) {
            messages.add(m);
        }
        return messages;
    }

    private static List<String> messageIds(List<Message> messages) {
        return messages.stream().map(m -> m.getMessageProperties().getMessageId()).toList();
    }

    private void awaitLeaseExpiry(String externalId) {
        await().atMost(Duration.ofSeconds(LEASE_SECONDS + 5)).pollInterval(Duration.ofMillis(100)).until(() ->
                jdbc.queryForObject("SELECT next_attempt_at <= CURRENT_TIMESTAMP(3) FROM outbox WHERE aggregate_id = ?", Integer.class, externalId) == 1);
    }

    private DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    private void startBrokerAndReconnect() {
        docker().startContainerCmd(RABBIT.getContainerId()).exec();
        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().until(() -> {
            reconnectToRestartedBroker();
            return rabbitTemplate.execute(channel -> channel.isOpen());
        });
    }

    private void reconnectToRestartedBroker() {
        String port = docker().inspectContainerCmd(RABBIT.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(RABBIT_PORT))[0].getHostPortSpec();
        connectionFactory.setAddresses(RABBIT.getHost() + ":" + port);
        connectionFactory.resetConnection();
    }

    private static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("Process killed before the broker was contacted");
        }
    }

    @Test
    void crashBetweenTheDatabaseWriteAndTheEventPublishLosesNothingAndPublishesExactlyOnce() throws Exception {
        String id = ingestNewEmployee();
        long outboxId = rowId(id);
        log.info("[1] Employee {} and outbox row {} committed together: status={} attempts={}", id, outboxId, row(id).get("status"), row(id).get("attempts"));

        doThrow(new SimulatedCrash()).when(publisher).publish(any());
        log.info("[2] Running the relay. It claims the row, then the process dies before contacting the broker");
        assertThrows(SimulatedCrash.class, relay::relayOnce);
        reset(publisher);

        Map<String, Object> afterCrash = row(id);
        log.info("[3] After the crash: outbox row {} status={} attempts={} publishedAt={}, messages on the broker={}", outboxId,
                afterCrash.get("status"), afterCrash.get("attempts"), afterCrash.get("published_at"), drainQueue().size());
        assertEquals("PENDING", afterCrash.get("status"));
        assertEquals(0, afterCrash.get("attempts"));
        assertNull(afterCrash.get("published_at"));
        assertEquals("Demo Joiner", employee(ACME, id).get("name"), "the employee state survived the crash");
        assertEquals("ACTIVE", employee(ACME, id).get("status"));
        assertTrue(drainQueue().isEmpty(), "the broker was never contacted");

        assertEquals(0, relay.relayOnce(), "still leased, so a restart cannot publish it twice");
        log.info("[4] Restarted relay finds nothing yet: the row is hidden until the {} second lease of the dead relay ends", LEASE_SECONDS);

        awaitLeaseExpiry(id);
        log.info("[5] The lease expired. Running the restarted relay");
        assertEquals(1, relay.relayOnce());

        Map<String, Object> afterRestart = row(id);
        List<Message> delivered = drainQueue();
        log.info("[6] After the restart: outbox row {} status={} publishedAt={}, messages on the broker={} with ids {}", outboxId,
                afterRestart.get("status"), afterRestart.get("published_at"), delivered.size(), messageIds(delivered));
        assertEquals("PUBLISHED", afterRestart.get("status"));
        assertNotNull(afterRestart.get("published_at"));
        assertEquals(List.of(String.valueOf(outboxId)), messageIds(delivered), "published exactly once");

        assertEquals(0, relay.relayOnce());
        assertTrue(drainQueue().isEmpty(), "a further relay run publishes nothing");
        assertEquals(1, outboxCount(ACME, id), "no second outbox row");
    }

    @Test
    void brokerDownGrowsTheBacklogBacksOffLosesNothingAndDrainsWhenItReturns() throws Exception {
        String beforeOutage = ingestNewEmployee();
        relay.relayOnce();
        assertEquals("PUBLISHED", row(beforeOutage).get("status"));
        assertEquals(1, drainQueue().size());

        String first = ingestNewEmployee();
        docker().stopContainerCmd(RABBIT.getContainerId()).exec();
        try {
            relay.relayOnce();
            assertEquals("PENDING", row(first).get("status"));
            assertEquals(1, row(first).get("attempts"));
            LocalDateTime firstRetry = (LocalDateTime) row(first).get("next_attempt_at");

            String during = ingestNewEmployee();
            assertEquals("PENDING", row(during).get("status"), "ingest keeps working while the broker is down");

            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() -> {
                relay.relayOnce();
                return (int) row(first).get("attempts") >= 2;
            });
            assertEquals("PENDING", row(first).get("status"));
            LocalDateTime secondRetry = (LocalDateTime) row(first).get("next_attempt_at");
            assertTrue(secondRetry.isAfter(firstRetry.plusSeconds(1)), "next_attempt_at backs off");

            startBrokerAndReconnect();

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
                relay.relayOnce();
                return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status <> 'PUBLISHED'", Integer.class) == 0;
            });

            Set<String> delivered = new TreeSet<>(messageIds(drainQueue()));
            assertEquals(new TreeSet<>(List.of(String.valueOf(rowId(first)), String.valueOf(rowId(during)))), delivered, "nothing lost");
        } finally {
            if (!Boolean.TRUE.equals(docker().inspectContainerCmd(RABBIT.getContainerId()).exec().getState().getRunning())) {
                startBrokerAndReconnect();
            }
        }
    }

    @Test
    void anAcknowledgedPersistentMessageInADurableQueueSurvivesABrokerRestart() throws Exception {
        String id = ingestNewEmployee();
        relay.relayOnce();
        assertEquals("PUBLISHED", row(id).get("status"));

        docker().stopContainerCmd(RABBIT.getContainerId()).exec();
        startBrokerAndReconnect();

        assertEquals(List.of(String.valueOf(rowId(id))), messageIds(drainQueue()));
    }

    @Test
    void aPublishThatIsSentButNeverAcknowledgedLeavesTheRowPending() throws Exception {
        relayOneThroughTheBroker();

        String id = ingestNewEmployee();
        docker().pauseContainerCmd(RABBIT.getContainerId()).exec();
        try {
            relay.relayOnce();

            Map<String, Object> unacknowledged = row(id);
            assertEquals("PENDING", unacknowledged.get("status"), "an unacknowledged row must not be PUBLISHED");
            assertNull(unacknowledged.get("published_at"));
            assertEquals(1, unacknowledged.get("attempts"));
        } finally {
            docker().unpauseContainerCmd(RABBIT.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            relay.relayOnce();
            return "PUBLISHED".equals(row(id).get("status"));
        });
        assertEquals(Set.of(String.valueOf(rowId(id))), Set.copyOf(messageIds(drainQueue())));
    }

    private void relayOneThroughTheBroker() throws Exception {
        String warmUp = ingestNewEmployee();
        relay.relayOnce();
        assertEquals("PUBLISHED", row(warmUp).get("status"));
        drainQueue();
    }

    @Test
    void aMessageNoQueueWouldAcceptIsNotMarkedPublished() throws Exception {
        String id = ingestNewEmployee();
        rabbitAdmin.removeBinding(binding);
        try {
            relay.relayOnce();

            assertEquals("PENDING", row(id).get("status"), "the broker acknowledges these too, so the confirm alone is not enough");
            assertEquals(1, row(id).get("attempts"));
        } finally {
            rabbitAdmin.declareBinding(binding);
        }
    }

    @Test
    void reachingMaxAttemptsMovesTheRowToFailedExactlyOnceAndStopsRetrying(CapturedOutput output) throws Exception {
        String id = ingestNewEmployee();
        long outboxId = rowId(id);
        jdbc.update("UPDATE outbox SET attempts = ? WHERE id = ?", MAX_ATTEMPTS - 1, outboxId);
        doReturn(Set.of()).when(publisher).publish(any());

        assertEquals(1, relay.relayOnce());

        Map<String, Object> parked = row(id);
        assertEquals("FAILED", parked.get("status"));
        assertEquals(MAX_ATTEMPTS, parked.get("attempts"));
        assertNull(parked.get("published_at"));
        assertTrue(output.getAll().contains("OUTBOX_ROW_PARKED outboxId=" + outboxId + " eventType=EMPLOYEE_CREATED attempts=" + MAX_ATTEMPTS + " status=FAILED"));

        Thread.sleep(Duration.ofSeconds(LEASE_SECONDS + 1).toMillis());
        assertEquals(0, relay.relayOnce(), "a parked row is never claimed again");
        verify(publisher, times(1)).publish(any());
        assertEquals(MAX_ATTEMPTS, row(id).get("attempts"));
        assertEquals(1, outboxCount(ACME, id), "parked, not dropped");
    }

    @Test
    void oneAttemptBelowTheLimitIsStillARetry() throws Exception {
        String id = ingestNewEmployee();
        jdbc.update("UPDATE outbox SET attempts = ? WHERE id = ?", MAX_ATTEMPTS - 2, rowId(id));
        doReturn(Set.of()).when(publisher).publish(any());

        relay.relayOnce();

        assertEquals("PENDING", row(id).get("status"));
        assertEquals(MAX_ATTEMPTS - 1, row(id).get("attempts"));
    }

    private int requeue(String token) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/outbox/failed/requeue").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        return body(response).get("requeued").asInt();
    }

    @Test
    void anOperatorCanRequeueAParkedRowAndItIsThenPublished() throws Exception {
        String id = ingestNewEmployee();
        jdbc.update("UPDATE outbox SET status = 'FAILED', attempts = ?, next_attempt_at = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 DAY) WHERE id = ?", MAX_ATTEMPTS, rowId(id));
        assertEquals(0, relay.relayOnce(), "parked, so the relay leaves it alone");

        assertEquals(1, requeue(operator()));

        assertEquals("PENDING", row(id).get("status"));
        assertEquals(0, row(id).get("attempts"), "attempts reset, so it gets a full retry window again");
        assertEquals(1, relay.relayOnce(), "claimable straight away");
        assertEquals("PUBLISHED", row(id).get("status"));
        assertEquals(List.of(String.valueOf(rowId(id))), messageIds(drainQueue()));
    }

    @Test
    void requeueWithNothingParkedMovesNothing() throws Exception {
        String pending = ingestNewEmployee();

        assertEquals(0, requeue(operator()));

        assertEquals(0, row(pending).get("attempts"));
    }

    @Test
    void requeueOnlyMovesTheCallersOwnTenant() throws Exception {
        String prefix = unique("PARKED");
        jdbc.update("INSERT INTO outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, status, attempts) VALUES (1, 'EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '{}', 'FAILED', 4), (2, 'EMPLOYEE', ?, 'EMPLOYEE_UPDATED', '{}', 'FAILED', 4)",
                prefix + "-ACME", prefix + "-GLOBEX");

        assertEquals(1, requeue(tokenFor("GLOBEX", "globex.operator")));

        assertEquals("FAILED", row(prefix + "-ACME").get("status"), "another client's row must not move");
        assertEquals("PENDING", row(prefix + "-GLOBEX").get("status"));
    }

    @Test
    void onlyAnOperatorCanRequeue() throws Exception {
        for (String user : List.of("acme.employee", "acme.admin")) {
            MockHttpServletResponse response = mockMvc.perform(post("/api/v1/outbox/failed/requeue")
                    .header("Authorization", "Bearer " + tokenFor("ACME", user))).andReturn().getResponse();
            assertEquals(403, response.getStatus(), user);
        }
        assertEquals(401, mockMvc.perform(post("/api/v1/outbox/failed/requeue")).andReturn().getResponse().getStatus());
    }

    @Test
    void twoRelaysRunningConcurrentlyPublishEveryRowExactlyOnce() throws Exception {
        String prefix = unique("CONCURRENT");
        int rows = 500;
        insertRows(prefix, rows);
        Set<Long> expected = new TreeSet<>(jdbc.queryForList("SELECT id FROM outbox WHERE aggregate_id LIKE ?", Long.class, prefix + "%"));
        OutboxProperties properties = new OutboxProperties(500, 25, 10, 60, 5000);
        OutboxRelay relayA = new OutboxRelay(store, publisher, properties, metrics);
        OutboxRelay relayB = new OutboxRelay(store, publisher, properties, metrics);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> drainWith(relayA, start, prefix));
            Future<Integer> b = pool.submit(() -> drainWith(relayB, start, prefix));
            start.countDown();

            int claimedByA = a.get();
            int claimedByB = b.get();

            log.info("Relay A claimed {} rows and relay B claimed {} rows", claimedByA, claimedByB);
            assertEquals(rows, claimedByA + claimedByB, "every row claimed exactly once");
            assertTrue(claimedByA > 0 && claimedByB > 0, "both relays must really have worked: " + claimedByA + " and " + claimedByB);
        } finally {
            pool.shutdownNow();
        }

        List<String> delivered = messageIds(drainQueue());
        Map<String, Long> timesDelivered = delivered.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertEquals(rows, delivered.size(), "no row was published twice");
        assertTrue(timesDelivered.values().stream().allMatch(count -> count == 1));
        assertEquals(expected.stream().map(String::valueOf).collect(Collectors.toCollection(TreeSet::new)), new TreeSet<>(delivered));
        assertEquals(rows, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id LIKE ? AND status = 'PUBLISHED'", Integer.class, prefix + "%"));
    }

    private int drainWith(OutboxRelay relay, CountDownLatch start, String prefix) throws InterruptedException {
        start.await();
        int total = 0;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id LIKE ? AND status = 'PENDING'", Integer.class, prefix + "%") > 0) {
            int claimed = relay.relayOnce();
            total += claimed;
            if (claimed == 0) {
                Thread.sleep(5);
            }
        }
        return total;
    }

    @Test
    void aLeaseThatExpiresWithoutASettleIsReclaimedAndPublished() throws Exception {
        String id = ingestNewEmployee();

        List<ClaimedMessage> claimed = store.claim(10, LEASE_SECONDS);
        assertEquals(1, claimed.size(), "this relay claims the row and then dies");
        assertEquals(0, relay.relayOnce(), "hidden from others during the lease");
        assertEquals("PENDING", row(id).get("status"));

        awaitLeaseExpiry(id);

        assertEquals(1, relay.relayOnce());
        assertEquals("PUBLISHED", row(id).get("status"));
        assertEquals(List.of(String.valueOf(rowId(id))), messageIds(drainQueue()));
    }

    @Test
    void aCrashAfterPublishingButBeforeSettlingSendsTheSameIdAgainForConsumersToDeduplicate() throws Exception {
        String id = ingestNewEmployee();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedCrash();
        }).when(publisher).publish(any());

        assertThrows(SimulatedCrash.class, relay::relayOnce);
        reset(publisher);
        assertEquals("PENDING", row(id).get("status"));

        awaitLeaseExpiry(id);
        relay.relayOnce();

        assertEquals("PUBLISHED", row(id).get("status"));
        String outboxId = String.valueOf(rowId(id));
        assertEquals(List.of(outboxId, outboxId), messageIds(drainQueue()), "at-least-once, and the id is what lets a consumer tell");
    }

    @Test
    void thePublishedMessageCarriesIdsOnlyAndIsPersistentWithTheRowIdAndEventType() throws Exception {
        String id = unique("PAYLOAD");
        send(operator(), hr(id, "CREATE", 3, Map.of("name", "Demo Secret Name", "email", "secret@acme.example",
                "phone", "+919000006000", "officeCode", "BLR-WHF", "shiftCode", "S1")));
        send(operator(), event(unique("evt"), id, "TRANSPORT", "UPDATE", 1,
                Map.of("pickupLat", 12.345678, "pickupLng", 77.654321, "pickupLabel", "Secret Home Gate", "specialAssistance", "Secret Need")));

        relay.relayOnce();

        List<Message> messages = drainQueue();
        assertEquals(2, messages.size());
        for (Message message : messages) {
            String raw = new String(message.getBody(), StandardCharsets.UTF_8);
            for (String personal : List.of("Demo Secret Name", "secret@acme.example", "+919000006000", "12.345678", "77.654321", "Secret Home Gate", "Secret Need")) {
                assertFalse(raw.contains(personal), "the message contained " + personal);
            }
            JsonNode payload = objectMapper.readTree(raw);
            Set<String> keys = new TreeSet<>();
            payload.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("eventId", "eventType", "tenantId", "occurredAt", "employeeExternalId", "changedFields", "hrVersion"), keys);
            assertEquals(String.valueOf(payload.get("eventId").asLong()), message.getMessageProperties().getMessageId());
            assertEquals(payload.get("eventType").asText(), message.getMessageProperties().getReceivedRoutingKey());
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getReceivedDeliveryMode());
            assertEquals(id, payload.get("employeeExternalId").asText());
        }
    }

    @Test
    void theExchangeAndQueueAreDurable() {
        assertTrue(exchange.isDurable());
        assertFalse(exchange.isAutoDelete());
        assertTrue(queue.isDurable());
        assertFalse(queue.isAutoDelete());
        assertEquals("#", binding.getRoutingKey());
    }

    @Test
    void noTransactionIsOpenWhileTheBrokerIsBeingContacted() throws Exception {
        ingestNewEmployee();
        List<Boolean> transactionOpenDuringPublish = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            transactionOpenDuringPublish.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(publisher).publish(any());

        relay.relayOnce();

        assertEquals(List.of(false), transactionOpenDuringPublish);
    }

    @Test
    void noRowLockIsHeldWhileTheBrokerIsBeingContacted() throws Exception {
        String id = ingestNewEmployee();
        long outboxId = rowId(id);
        List<Long> lockedByAnotherConnection = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            lockedByAnotherConnection.add(jdbc.queryForObject("SELECT id FROM outbox WHERE id = ? FOR UPDATE NOWAIT", Long.class, outboxId));
            return invocation.callRealMethod();
        }).when(publisher).publish(any());

        relay.relayOnce();

        assertEquals(List.of(outboxId), lockedByAnotherConnection, "the row must not stay locked during the network call");
    }

    @Test
    void aRelayWithNothingToDoDoesNotContactTheBroker() {
        assertEquals(0, relay.relayOnce());

        verify(publisher, never()).publish(any());
    }

    @Test
    void aLeaseShorterThanTheConfirmTimeoutIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelay(store, publisher, new OutboxProperties(500, 25, 10, 1, 5000), metrics));
    }
}

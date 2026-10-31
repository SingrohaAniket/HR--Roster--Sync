package com.moveinsync.roster.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.config.IngestProperties;
import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.domain.EventOutcome;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.office.OfficeDirectory;
import com.moveinsync.roster.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class IngestServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RuntimeException DATABASE_DOWN = new CannotCreateTransactionException("Could not open a connection");
    private static final RuntimeException DEADLOCK = new CannotAcquireLockException("Deadlock found");
    private static final ApplyResult APPLIED = ApplyResult.of(EventOutcome.APPLIED);

    private final EventApplier applier = mock(EventApplier.class);
    private final OfficeDirectory offices = mock(OfficeDirectory.class);
    private final IngestService service = new IngestService(applier, mock(RosterEventRepository.class), offices, new IngestProperties(1000),
            new RosterMetrics(new SimpleMeterRegistry()));

    @BeforeEach
    void setTenant() {
        TenantContext.set(1);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static JsonNode valid(int i) throws Exception {
        return MAPPER.readTree("{\"eventId\":\"e" + i + "\",\"employeeExternalId\":\"E" + i
                + "\",\"source\":\"HR\",\"eventType\":\"UPDATE\",\"version\":1,\"payload\":{\"name\":\"N\"}}");
    }

    private static List<JsonNode> validEvents(int count) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(valid(i));
        }
        return events;
    }

    private static List<EventOutcome> outcomes(IngestResponse response) {
        return response.results().stream().map(EventResult::outcome).toList();
    }

    @Test
    void aDatabaseOutageAbandonsTheBatchAfterThreeFailures() throws Exception {
        when(applier.apply(any(), any())).thenThrow(DATABASE_DOWN);

        IngestResponse response = service.ingest(validEvents(1000));

        verify(applier, times(3)).apply(any(), any());
        assertEquals(1000, response.received());
        assertEquals(3, response.rejected());
        assertEquals(997, response.notProcessed());
        assertEquals(0, response.applied());
        assertEquals(ErrorCode.PROCESSING_FAILED, response.results().get(2).errors().get(0).getCode());
        assertEquals(EventOutcome.NOT_PROCESSED, response.results().get(3).outcome());
        assertEquals(0, response.results().get(3).errors().size());
        assertEquals("e999", response.results().get(999).eventId());
    }

    @Test
    void recordsBeforeTheOutageKeepTheirOutcome() throws Exception {
        when(applier.apply(any(), any())).thenReturn(APPLIED, APPLIED).thenThrow(DATABASE_DOWN);

        IngestResponse response = service.ingest(validEvents(8));

        assertEquals(List.of(EventOutcome.APPLIED, EventOutcome.APPLIED,
                EventOutcome.REJECTED, EventOutcome.REJECTED, EventOutcome.REJECTED,
                EventOutcome.NOT_PROCESSED, EventOutcome.NOT_PROCESSED, EventOutcome.NOT_PROCESSED), outcomes(response));
    }

    @Test
    void aSuccessBetweenFailuresResetsTheCount() throws Exception {
        when(applier.apply(any(), any()))
                .thenThrow(DATABASE_DOWN).thenThrow(DATABASE_DOWN).thenReturn(APPLIED)
                .thenThrow(DATABASE_DOWN).thenThrow(DATABASE_DOWN).thenReturn(APPLIED);

        IngestResponse response = service.ingest(validEvents(6));

        assertEquals(0, response.notProcessed());
        assertEquals(2, response.applied());
        assertEquals(4, response.rejected());
    }

    @Test
    void validationFailuresNeitherCountNorResetIt() throws Exception {
        JsonNode invalid = MAPPER.readTree("{\"eventId\":\"bad\"}");
        when(applier.apply(any(), any())).thenThrow(DATABASE_DOWN);

        IngestResponse manyInvalid = service.ingest(List.of(invalid, invalid, invalid, invalid, invalid, valid(1)));
        assertEquals(0, manyInvalid.notProcessed(), "invalid records say nothing about the database");

        IngestResponse mixed = service.ingest(List.of(valid(1), valid(2), invalid, valid(3), valid(4)));
        assertEquals(List.of(EventOutcome.REJECTED, EventOutcome.REJECTED, EventOutcome.REJECTED, EventOutcome.REJECTED,
                EventOutcome.NOT_PROCESSED), outcomes(mixed), "an invalid record does not reset the count");
    }

    @Test
    void anInvalidRecordAfterTheAbortIsStillRejectedBecauseThatNeedsNoDatabase() throws Exception {
        when(applier.apply(any(), any())).thenThrow(DATABASE_DOWN);
        List<JsonNode> events = validEvents(3);
        events.add(MAPPER.readTree("{\"eventId\":\"e\",\"employeeExternalId\":\"E\",\"source\":\"HR\",\"eventType\":\"UPDATE\",\"version\":0}"));
        events.add(MAPPER.readTree("{\"employeeExternalId\":\"E\"}"));

        IngestResponse response = service.ingest(events);

        assertEquals(ErrorCode.INVALID_VERSION, response.results().get(3).errors().get(0).getCode());
        assertNull(response.results().get(4).eventId());
    }

    @Test
    void anOrdinaryFailureDoesNotAbandonTheBatch() throws Exception {
        when(applier.apply(any(), any())).thenThrow(new IllegalStateException("a bug in one record"));

        IngestResponse response = service.ingest(validEvents(20));

        verify(applier, times(20)).apply(any(), any());
        assertEquals(20, response.rejected());
        assertEquals(0, response.notProcessed());
    }

    @Test
    void aConnectionErrorWrappedInsideAnotherExceptionStillCounts() throws Exception {
        when(applier.apply(any(), any())).thenThrow(new RuntimeException(new SQLTransientConnectionException("pool timeout")));

        assertEquals(7, service.ingest(validEvents(10)).notProcessed());
    }

    @Test
    void anOutageDuringTheOfficeLookupCountsToo() throws Exception {
        when(offices.findActive(anyLong(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        List<JsonNode> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(MAPPER.readTree("{\"eventId\":\"e" + i + "\",\"employeeExternalId\":\"E\",\"source\":\"HR\",\"eventType\":\"UPDATE\",\"version\":1,\"payload\":{\"officeCode\":\"X\"}}"));
        }

        IngestResponse response = service.ingest(events);

        assertEquals(3, response.rejected());
        assertEquals(7, response.notProcessed());
        verify(applier, never()).apply(any(), any());
    }

    @Test
    void aDeadlockIsRetriedAfterARandomisedWait() throws Exception {
        when(applier.apply(any(), any())).thenThrow(DEADLOCK).thenThrow(DEADLOCK).thenReturn(APPLIED);

        long started = System.nanoTime();
        IngestResponse response = service.ingest(validEvents(1));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        verify(applier, times(3)).apply(any(), any());
        assertEquals(1, response.applied());
        assertTrue(elapsedMillis >= 20, "two retries wait at least 10 ms each, but took " + elapsedMillis + " ms");
    }

    @Test
    void aRecordThatLosesEveryRetryStaysProcessingFailed() throws Exception {
        when(applier.apply(any(), any())).thenThrow(DEADLOCK);

        IngestResponse response = service.ingest(validEvents(1));

        verify(applier, times(5)).apply(any(), any());
        assertEquals(EventOutcome.REJECTED, response.results().get(0).outcome());
        assertEquals(ErrorCode.PROCESSING_FAILED, response.results().get(0).errors().get(0).getCode());
    }

    @Test
    void aDeadlockDoesNotCountAsADatabaseOutage() throws Exception {
        when(applier.apply(any(), any())).thenThrow(DEADLOCK);

        assertEquals(0, service.ingest(validEvents(4)).notProcessed());
    }

    @Test
    void anInterruptedThreadStopsRetryingAndKeepsItsInterruptFlag() throws Exception {
        when(applier.apply(any(), any())).thenThrow(DEADLOCK);

        Thread.currentThread().interrupt();
        IngestResponse response = service.ingest(validEvents(1));

        assertTrue(Thread.interrupted(), "the interrupt must survive for whoever owns this thread");
        verify(applier, times(1)).apply(any(), any());
        assertEquals(ErrorCode.PROCESSING_FAILED, response.results().get(0).errors().get(0).getCode());
    }
}

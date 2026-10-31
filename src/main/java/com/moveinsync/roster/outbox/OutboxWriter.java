package com.moveinsync.roster.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.core.validation.ValidatedField;
import com.moveinsync.roster.domain.Booking;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.OutboxMessage;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWriter {

    private static final String AGGREGATE_EMPLOYEE = "EMPLOYEE";
    private static final String AGGREGATE_BOOKING = "BOOKING";

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(Employee employee, OutboxEventType type, Collection<FieldKey> changedFields, Instant occurredAt) {
        insert(employee, AGGREGATE_EMPLOYEE, employee.getExternalId(), type, changedFields, occurredAt, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void writeBookingCancelled(Employee employee, Booking booking, Instant occurredAt) {
        insert(employee, AGGREGATE_BOOKING, String.valueOf(booking.getId()), OutboxEventType.BOOKING_CANCELLED,
                List.of(FieldKey.STATUS), occurredAt, booking.getId(), booking.getTrip().getId());
    }

    public record BatchEvent(long tenantId, String externalId, OutboxEventType type, Collection<FieldKey> changedFields,
                             Instant occurredAt, long hrVersion) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void writeBatch(List<BatchEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.batchUpdate(connection -> connection.prepareStatement("""
                INSERT INTO outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                """, Statement.RETURN_GENERATED_KEYS), new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                BatchEvent event = events.get(i);
                ps.setLong(1, event.tenantId());
                ps.setString(2, AGGREGATE_EMPLOYEE);
                ps.setString(3, event.externalId());
                ps.setString(4, event.type().name());
                ps.setString(5, toJson(new Payload(0, event.type(), event.tenantId(), event.occurredAt(), event.externalId(),
                        event.changedFields().stream().map(field -> ValidatedField.of(field).apiName()).toList(),
                        event.hrVersion(), null, null)));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        }, keys);
        List<Long> ids = keys.getKeyList().stream().map(key -> ((Number) key.values().iterator().next()).longValue()).toList();
        jdbc.update("UPDATE outbox SET payload = JSON_SET(payload, '$.eventId', id) WHERE id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", ids.toArray());
    }

    private void insert(Employee employee, String aggregateType, String aggregateId, OutboxEventType type,
                        Collection<FieldKey> changedFields, Instant occurredAt, Long bookingId, Long tripId) {
        OutboxMessage message = outbox.save(new OutboxMessage(employee.getTenantId(), aggregateType, aggregateId, type.name(), "{}"));
        message.setPayload(toJson(new Payload(
                message.getId(),
                type,
                employee.getTenantId(),
                occurredAt,
                employee.getExternalId(),
                changedFields.stream().map(field -> ValidatedField.of(field).apiName()).toList(),
                employee.getHrVersion(),
                bookingId,
                tripId)));
    }

    private String toJson(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise an outbox payload", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Payload(
            long eventId,
            OutboxEventType eventType,
            long tenantId,
            Instant occurredAt,
            String employeeExternalId,
            List<String> changedFields,
            long hrVersion,
            Long bookingId,
            Long tripId) {
    }
}

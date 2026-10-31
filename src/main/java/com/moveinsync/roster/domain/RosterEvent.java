package com.moveinsync.roster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "roster_event")
public class RosterEvent extends BaseEntity {

    private Long tenantId;

    private String idempotencyKey;

    private String employeeExternalId;

    @Enumerated(EnumType.STRING)
    private SourceSystem source;

    @Enumerated(EnumType.STRING)
    private RosterEventType eventType;

    private long sourceVersion;

    @Column(columnDefinition = "char(64)")
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    private EventOutcome outcome;

    @CreationTimestamp
    private Instant receivedAt;

    private Instant processedAt;

    protected RosterEvent() {
    }

    public RosterEvent(Long tenantId, String idempotencyKey, String employeeExternalId, SourceSystem source,
                       RosterEventType eventType, long sourceVersion, String payloadHash) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.employeeExternalId = employeeExternalId;
        this.source = source;
        this.eventType = eventType;
        this.sourceVersion = sourceVersion;
        this.payloadHash = payloadHash;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getEmployeeExternalId() {
        return employeeExternalId;
    }

    public SourceSystem getSource() {
        return source;
    }

    public RosterEventType getEventType() {
        return eventType;
    }

    public long getSourceVersion() {
        return sourceVersion;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public EventOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(EventOutcome outcome) {
        this.outcome = outcome;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}

package com.moveinsync.roster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox")
public class OutboxMessage extends BaseEntity {

    private Long tenantId;

    private String aggregateType;

    private String aggregateId;

    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Enumerated(EnumType.STRING)
    private OutboxStatus status = OutboxStatus.PENDING;

    private int attempts;

    private Instant nextAttemptAt = Instant.now();

    @CreationTimestamp
    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxMessage() {
    }

    public OutboxMessage(Long tenantId, String aggregateType, String aggregateId, String eventType, String payload) {
        this.tenantId = tenantId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}

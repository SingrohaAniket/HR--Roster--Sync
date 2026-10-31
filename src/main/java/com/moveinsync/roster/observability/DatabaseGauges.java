package com.moveinsync.roster.observability;

import com.moveinsync.roster.domain.OutboxStatus;
import com.moveinsync.roster.outbox.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseGauges implements MeterBinder {

    private static final String LAST_FINISHED_RUN_MISSING_PERCENT = """
            SELECT missing_percent FROM reconciliation_run
             WHERE status <> 'RUNNING'
             ORDER BY id DESC
             LIMIT 1
            """;

    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;

    public DatabaseGauges(OutboxRepository outbox, JdbcTemplate jdbc) {
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("roster.outbox.pending", this, DatabaseGauges::pending)
                .description("Queue depth: rows waiting to be published. Rising means the relay is losing")
                .register(registry);
        Gauge.builder("roster.outbox.lag.seconds", this, DatabaseGauges::lagSeconds)
                .description("Age of the oldest pending row. The real SLA signal: a small queue that is not draining is worse than a large one that is")
                .register(registry);
        Gauge.builder("roster.reconciliation.missing.percent", this, DatabaseGauges::lastMissingPercent)
                .description("How close the last finished run came to the safety brake")
                .register(registry);
    }

    double pending() {
        return outbox.countByStatus(OutboxStatus.PENDING);
    }

    double lagSeconds() {
        return outbox.findOldestPendingCreatedAt()
                .map(oldest -> Math.max(0, Duration.between(oldest, Instant.now()).toMillis() / 1000.0))
                .orElse(0.0);
    }

    double lastMissingPercent() {
        return jdbc.query(LAST_FINISHED_RUN_MISSING_PERCENT, (rs, n) -> rs.getBigDecimal(1)).stream()
                .findFirst().map(BigDecimal::doubleValue).orElse(0.0);
    }
}

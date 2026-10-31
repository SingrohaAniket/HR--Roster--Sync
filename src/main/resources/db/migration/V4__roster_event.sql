CREATE TABLE roster_event (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT       NOT NULL,
    idempotency_key      VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    employee_external_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    source               ENUM('HR', 'TRANSPORT') NOT NULL,
    event_type           ENUM('CREATE', 'UPDATE', 'DEACTIVATE') NOT NULL,
    source_version       BIGINT       NOT NULL,
    payload_hash         CHAR(64)     NOT NULL,
    outcome              ENUM('APPLIED', 'IGNORED_STALE', 'DUPLICATE_NOOP', 'IGNORED_NO_OWNED_FIELDS', 'REJECTED') NULL,
    received_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_at         DATETIME(3)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_roster_event_tenant_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_roster_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    INDEX idx_roster_event_tenant_employee_received (tenant_id, employee_external_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

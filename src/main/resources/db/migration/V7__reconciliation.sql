CREATE TABLE reconciliation_run (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT        NOT NULL,
    started_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at         DATETIME(3)   NULL,
    status              ENUM('RUNNING', 'COMPLETED', 'AWAITING_APPROVAL', 'ABORTED', 'FAILED') NOT NULL DEFAULT 'RUNNING',
    export_generated_at DATETIME(3)   NOT NULL,
    export_row_count    INT           NOT NULL DEFAULT 0,
    active_count_before INT           NOT NULL DEFAULT 0,
    missing_count       INT           NOT NULL DEFAULT 0,
    missing_percent     DECIMAL(5, 2) NOT NULL DEFAULT 0.00,
    threshold_percent   DECIMAL(5, 2) NOT NULL,
    created_count       INT           NOT NULL DEFAULT 0,
    updated_count       INT           NOT NULL DEFAULT 0,
    deactivated_count   INT           NOT NULL DEFAULT 0,
    abort_reason        VARCHAR(255)  NULL,
    approved_by         VARCHAR(100)  NULL,
    approved_at         DATETIME(3)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reconciliation_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reconciliation_pending_deactivation (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    run_id      BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pending_deactivation_run FOREIGN KEY (run_id) REFERENCES reconciliation_run (id),
    CONSTRAINT fk_pending_deactivation_employee FOREIGN KEY (employee_id) REFERENCES employee (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

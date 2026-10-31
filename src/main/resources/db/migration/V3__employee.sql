CREATE TABLE employee (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT       NOT NULL,
    external_id          VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,

    name                 VARCHAR(255) NULL,
    email                VARCHAR(320) NULL,
    phone                VARCHAR(16)  NULL,
    manager_external_id  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    office_id            BIGINT       NULL,
    shift_code           VARCHAR(32)  NULL,

    pickup_lat           DECIMAL(9, 6) NULL,
    pickup_lng           DECIMAL(9, 6) NULL,
    pickup_label         VARCHAR(255)  NULL,
    special_assistance   VARCHAR(500)  NULL,

    status               ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',

    hr_version           BIGINT       NOT NULL DEFAULT 0,
    transport_version    BIGINT       NOT NULL DEFAULT 0,
    hr_updated_at        DATETIME(3)  NULL,
    transport_updated_at DATETIME(3)  NULL,

    content_hash         CHAR(64)     NOT NULL,
    bucket               SMALLINT UNSIGNED NOT NULL,

    deactivated_at       DATETIME(3)  NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT uq_employee_tenant_external UNIQUE (tenant_id, external_id),
    CONSTRAINT fk_employee_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_employee_office FOREIGN KEY (office_id) REFERENCES office (id),
    INDEX idx_employee_tenant_bucket_status (tenant_id, bucket, status),
    INDEX idx_employee_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

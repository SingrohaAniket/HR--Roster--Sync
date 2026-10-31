CREATE TABLE app_user (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT       NOT NULL,
    username             VARCHAR(100) NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    role                 ENUM('EMPLOYEE', 'ADMIN', 'OPERATOR') NOT NULL,
    employee_external_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_app_user_tenant_username UNIQUE (tenant_id, username),
    CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_app_user_employee_link CHECK (role = 'EMPLOYEE' OR employee_external_id IS NULL)
) ENGINE=InnoDB DEFAULT  CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

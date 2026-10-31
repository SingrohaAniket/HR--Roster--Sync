CREATE TABLE trip (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    trip_date   DATE        NOT NULL,
    shift_code  VARCHAR(32) NOT NULL,
    office_id   BIGINT      NOT NULL,
    total_seats INT         NOT NULL,
    seats_taken INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_trip_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_trip_office FOREIGN KEY (office_id) REFERENCES office (id),
    CONSTRAINT chk_trip_seats CHECK (seats_taken >= 0 AND seats_taken <= total_seats)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE booking (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    employee_id   BIGINT      NOT NULL,
    trip_id       BIGINT      NOT NULL,
    trip_date     DATE        NOT NULL,
    status        ENUM('BOOKED', 'CANCELLED') NOT NULL DEFAULT 'BOOKED',
    cancel_reason VARCHAR(64) NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    cancelled_at  DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_booking_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_booking_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT fk_booking_trip FOREIGN KEY (trip_id) REFERENCES trip (id),
    INDEX idx_booking_tenant_employee_date_status (tenant_id, employee_id, trip_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE reconciliation_run
    ADD COLUMN reactivated_count    INT NOT NULL DEFAULT 0,
    ADD COLUMN skipped_count        INT NOT NULL DEFAULT 0,
    ADD COLUMN unchanged_count      INT NOT NULL DEFAULT 0,
    ADD COLUMN malformed_row_count  INT NOT NULL DEFAULT 0,
    ADD COLUMN unknown_office_count INT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_reconciliation_one_open_run ON reconciliation_run
    ((CASE WHEN status IN ('RUNNING', 'AWAITING_APPROVAL') THEN tenant_id END));

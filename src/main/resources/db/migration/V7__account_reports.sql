-- =====================================================
-- ACCOUNT STATEMENT REPORTING
--
-- Two execution paths:
--   1. Past reports (to_date < today UTC): frozen once in
--      past_account_reports and served statically.
--   2. Current reports (to_date >= today UTC): queued via
--      Redis Streams and tracked in report_jobs.
-- =====================================================

-- Covering index for opening-balance SUMs and chronological window scans.
CREATE INDEX idx_ledger_account_created_at
    ON ledger_entries (account_id, created_at, id);

CREATE TABLE past_account_reports (
    report_id         UUID         PRIMARY KEY,
    account_id        BIGINT       NOT NULL REFERENCES accounts(id),
    from_date         DATE         NOT NULL,
    to_date           DATE         NOT NULL,
    opening_balance   BIGINT       NOT NULL,
    closing_balance   BIGINT       NOT NULL,
    total_credits     BIGINT       NOT NULL,
    total_debits      BIGINT       NOT NULL,
    total_adjustments BIGINT       NOT NULL,
    net_change        BIGINT       NOT NULL,
    entry_count       INT          NOT NULL,
    entries_json      JSONB        NOT NULL,
    frozen_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_past_account_report_window
        UNIQUE (account_id, from_date, to_date),

    CONSTRAINT chk_past_report_dates
        CHECK (from_date <= to_date)
);

CREATE TABLE report_jobs (
    report_id         UUID         PRIMARY KEY,
    account_id        BIGINT       NOT NULL REFERENCES accounts(id),
    from_date         DATE         NOT NULL,
    to_date           DATE         NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    opening_balance   BIGINT,
    closing_balance   BIGINT,
    total_credits     BIGINT,
    total_debits      BIGINT,
    total_adjustments BIGINT,
    net_change        BIGINT,
    entry_count       INT,
    entries_json      JSONB,
    error_message     VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    CONSTRAINT chk_report_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),

    CONSTRAINT chk_report_job_dates
        CHECK (from_date <= to_date)
);

CREATE INDEX idx_report_jobs_account_created
    ON report_jobs (account_id, created_at DESC);

-- =====================================================
-- TRANSFER FAILURE METADATA
--
-- Persist the category and attempt count for terminal
-- FAILED rows so a future DLQ can distinguish
-- deterministic business rejections from retry-exhausted
-- transient contention, without re-introducing
-- silent auto-retry now.
--
-- Existing FAILED rows stay failure_category=NULL
-- ("unknown, pre-tagging"). New failures are tagged
-- by the writer (see TransferRecoveryService).
-- =====================================================

ALTER TABLE transfers ADD COLUMN failure_category VARCHAR(20);

ALTER TABLE transfers
    ADD CONSTRAINT chk_failure_category
    CHECK (failure_category IS NULL
        OR failure_category IN ('BUSINESS','TRANSIENT','TIMEOUT'));

ALTER TABLE transfers ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

CREATE INDEX idx_transfers_failed_category
    ON transfers(status, failure_category) WHERE status = 'FAILED';

COMMENT ON COLUMN transfers.failure_category IS 'BUSINESS / TRANSIENT / TIMEOUT (NULL = not failed or pre-V4)';
COMMENT ON COLUMN transfers.attempt_count IS 'Attempts before terminal failure (1=business, 3=transient-exhausted, 1=timeout sweep)';

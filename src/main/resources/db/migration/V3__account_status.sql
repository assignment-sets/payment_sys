-- =====================================================
-- ACCOUNT STATUS
--
-- ACTIVE  — normal operations
-- FROZEN  — blocks outbound transfers (debits) only;
--           inbound credits and reconciliation ADJUSTMENT
--           entries remain allowed. Un-freezing is a
--           manual ops action.
-- =====================================================

ALTER TABLE accounts
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE accounts
    ADD CONSTRAINT chk_account_status
    CHECK (status IN ('ACTIVE', 'FROZEN'));

COMMENT ON COLUMN accounts.status IS 'ACTIVE / FROZEN';

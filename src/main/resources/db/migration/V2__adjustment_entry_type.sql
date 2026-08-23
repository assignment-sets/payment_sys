-- =====================================================
-- ADJUSTMENT ENTRY TYPE
--
-- Drift resolution goes through immutable ledger entries,
-- never silent balance overrides. ADJUSTMENT entries:
--   - are not tied to a transfer (transaction_id is a
--     generated adjustment id, column stays NOT NULL)
--   - may carry either sign, unlike DEBIT (<0) / CREDIT (>0)
-- =====================================================

ALTER TABLE ledger_entries DROP CONSTRAINT chk_entry_type;

ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_entry_type
    CHECK (entry_type IN ('DEBIT', 'CREDIT', 'ADJUSTMENT'));

-- ADJUSTMENT entries may be either sign; only zero is invalid.
ALTER TABLE ledger_entries DROP CONSTRAINT chk_amount_sign;

ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_amount_sign
    CHECK (
        entry_type = 'ADJUSTMENT' OR
        (entry_type = 'DEBIT' AND amount < 0) OR
        (entry_type = 'CREDIT' AND amount > 0)
    );

-- Keep the column comment honest about the new value.
COMMENT ON COLUMN ledger_entries.entry_type IS 'DEBIT / CREDIT / ADJUSTMENT';

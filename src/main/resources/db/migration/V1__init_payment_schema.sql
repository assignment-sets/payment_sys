-- =====================================================
-- USERS
-- =====================================================
CREATE TABLE users (
                       id              BIGSERIAL PRIMARY KEY,
                       name            VARCHAR(100) NOT NULL,
                       email           VARCHAR(255) NOT NULL UNIQUE,
                       created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================
-- ACCOUNTS
-- =====================================================
CREATE TABLE accounts (
                          id              BIGSERIAL PRIMARY KEY,
                          user_id         BIGINT NOT NULL,
                          balance         BIGINT NOT NULL DEFAULT 0, -- smallest currency unit (e.g. cents)
                          version         BIGINT NOT NULL DEFAULT 0, -- for optimistic locking
                          created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

                          CONSTRAINT fk_accounts_user
                              FOREIGN KEY (user_id)
                                  REFERENCES users(id)
);

-- =====================================================
-- TRANSFERS (Business-level transaction)
-- =====================================================
CREATE TABLE transfers (
                           id                  BIGSERIAL PRIMARY KEY,
                           transaction_id      UUID NOT NULL,
                           from_account_id     BIGINT NOT NULL,
                           to_account_id       BIGINT NOT NULL,
                           amount              BIGINT NOT NULL CHECK (amount > 0),
                           status              VARCHAR(20) NOT NULL,
                           idempotency_key     VARCHAR(255) NOT NULL,
                           created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

                           CONSTRAINT uq_transaction_id
                               UNIQUE (transaction_id),

                           CONSTRAINT uq_idempotency
                               UNIQUE (from_account_id, idempotency_key),

                           CONSTRAINT fk_transfer_from_account
                               FOREIGN KEY (from_account_id)
                                   REFERENCES accounts(id),

                           CONSTRAINT fk_transfer_to_account
                               FOREIGN KEY (to_account_id)
                                   REFERENCES accounts(id),

                           CONSTRAINT chk_different_accounts
                               CHECK (from_account_id <> to_account_id)
);

-- =====================================================
-- LEDGER ENTRIES (Append-only source of truth)
-- =====================================================
CREATE TABLE ledger_entries (
                                id                  BIGSERIAL PRIMARY KEY,
                                transaction_id      UUID NOT NULL,
                                account_id          BIGINT NOT NULL,
                                amount              BIGINT NOT NULL, -- positive or negative
                                entry_type          VARCHAR(10) NOT NULL, -- DEBIT / CREDIT
                                created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

                                CONSTRAINT fk_ledger_account
                                    FOREIGN KEY (account_id)
                                        REFERENCES accounts(id)
);

-- =====================================================
-- INDEXES (Performance + correctness)
-- =====================================================
CREATE INDEX idx_ledger_account_id
    ON ledger_entries(account_id);

CREATE INDEX idx_ledger_transaction_id
    ON ledger_entries(transaction_id);

CREATE INDEX idx_transfer_from_account
    ON transfers(from_account_id);

CREATE INDEX idx_transfer_to_account
    ON transfers(to_account_id);

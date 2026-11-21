-- =====================================================
-- USER AUTHENTICATION & ROLES
--
-- Backfills pre-existing rows with an unmatchable non-BCrypt
-- sentinel so legacy rows cannot authenticate, then drops the
-- default on password_hash.
-- =====================================================

ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(255) NOT NULL DEFAULT '!PRE_AUTH_ACCOUNT',
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

ALTER TABLE users
    ADD CONSTRAINT chk_user_role
    CHECK (role IN ('USER', 'ADMIN'));

ALTER TABLE users
    ALTER COLUMN password_hash DROP DEFAULT;

COMMENT ON COLUMN users.role IS 'USER / ADMIN';

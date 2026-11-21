-- Notify backend cache listeners whenever an account's balance, status, or version changes.
CREATE OR REPLACE FUNCTION notify_account_changed()
RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify(
        'account_changed',
        NEW.id || ':' || NEW.user_id || ':' || NEW.version
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_account_changed
    AFTER INSERT OR UPDATE OF balance, status, version ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION notify_account_changed();

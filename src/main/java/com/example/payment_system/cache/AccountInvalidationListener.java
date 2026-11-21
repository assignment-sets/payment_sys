package com.example.payment_system.cache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens on PostgreSQL {@code LISTEN account_changed} notifications emitted by
 * {@code trg_account_changed} and invalidates the corresponding versioned Redis
 * account cache entry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${cache.enabled:true} and ${cache.account.notify.enabled:true}")
public class AccountInvalidationListener {

    private static final String CHANNEL = "account_changed";

    private final DataSource dataSource;
    private final AccountCacheService accountCacheService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listenerThread;

    @PostConstruct
    public void start() {
        running.set(true);
        listenerThread = Thread.ofVirtual()
                .name("pg-account-notify-listener")
                .start(this::listenLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }

    private void listenLoop() {
        while (running.get()) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                PGConnection pgConn = conn.unwrap(PGConnection.class);
                stmt.execute("LISTEN " + CHANNEL);
                log.info("Listening on PostgreSQL channel '{}'", CHANNEL);

                while (running.get() && !conn.isClosed()) {
                    PGNotification[] notifications = pgConn.getNotifications(1000);
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            handlePayload(notification.getParameter());
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("PostgreSQL LISTEN '{}' disconnected, reconnecting in 2s: {}", CHANNEL, e.getMessage());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    void handlePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        String[] parts = payload.split(":");
        if (parts.length < 3) {
            log.warn("Ignoring malformed account_changed payload: {}", payload);
            return;
        }
        try {
            long accountId = Long.parseLong(parts[0]);
            long version = Long.parseLong(parts[2]);
            accountCacheService.invalidate(accountId, version);
        } catch (NumberFormatException e) {
            log.warn("Invalid numbers in account_changed payload: {}", payload);
        }
    }
}

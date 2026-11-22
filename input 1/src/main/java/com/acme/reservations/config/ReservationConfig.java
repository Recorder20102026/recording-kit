package com.acme.reservations.config;

import com.acme.reservations.db.DbConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Typed view of {@code config/reservation.properties}.
 * Override the location with {@code -Dreservation.config=/path/to/file}.
 */
public record ReservationConfig(Duration lockWaitTimeout,
                                long statementLatencyMicros,
                                long commitLatencyMicros,
                                long paymentLatencyMinMillis,
                                long paymentLatencyMaxMillis,
                                long eventBusLatencyMillis,
                                int clusterNodes,
                                boolean localLockEnabled,
                                int maxRetries,
                                Duration retryBackoff) {

    public static ReservationConfig load() {
        Path path = Path.of(System.getProperty("reservation.config", "config/reservation.properties"));
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + path.toAbsolutePath(), e);
            }
        } else {
            System.err.println("[config] " + path.toAbsolutePath() + " not found - using defaults");
        }
        return new ReservationConfig(
                Duration.ofMillis(longOf(p, "db.lock.wait.timeout.ms", 50)),
                longOf(p, "db.statement.latency.micros", 300),
                longOf(p, "db.commit.latency.micros", 500),
                longOf(p, "payment.latency.min.ms", 3),
                longOf(p, "payment.latency.max.ms", 6),
                longOf(p, "eventbus.latency.ms", 1),
                (int) longOf(p, "cluster.nodes", 3),
                Boolean.parseBoolean(p.getProperty("reservation.local.lock.enabled", "true").trim()),
                (int) longOf(p, "retry.max.retries", 5),
                Duration.ofMillis(longOf(p, "retry.backoff.ms", 10)));
    }

    public DbConfig dbConfig() {
        return new DbConfig(lockWaitTimeout, statementLatencyMicros, commitLatencyMicros);
    }

    private static long longOf(Properties p, String key, long defaultValue) {
        String value = p.getProperty(key);
        return value == null ? defaultValue : Long.parseLong(value.trim());
    }
}

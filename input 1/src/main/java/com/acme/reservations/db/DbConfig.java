package com.acme.reservations.db;

import java.time.Duration;

/**
 * Settings of the shared PostgreSQL primary that every service node talks to.
 *
 * @param lockWaitTimeout       {@code SET lock_timeout} - mandated by the DBA team (see docs/INCIDENT-2291.md)
 * @param statementLatencyMicros simulated network round trip per SQL statement
 * @param commitLatencyMicros    simulated WAL flush / COMMIT round trip
 */
public record DbConfig(Duration lockWaitTimeout, long statementLatencyMicros, long commitLatencyMicros) {
}

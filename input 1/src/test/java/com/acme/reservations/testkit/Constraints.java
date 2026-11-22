package com.acme.reservations.testkit;

import java.time.Duration;

/**
 * Non-negotiable platform constraints the reservation path must satisfy (see docs/INCIDENT-2291.md).
 * These are NOT read from config on purpose - config is what we change, these are what we are measured against.
 */
public final class Constraints {

    /** DBA rule: {@code lock_timeout = 50ms}. No request may wait longer than this for a row lock. */
    public static final Duration LOCK_WAIT_BUDGET = Duration.ofMillis(50);

    /** Measurement slack on top of the lock budget (thread wake-up after the timeout fires). */
    public static final double LOCK_WAIT_TOLERANCE_MILLIS = 2.0;

    /** SRE rule: at most 2 retries (3 attempts) per request, to stop retry storms. */
    public static final int MAX_RETRIES = 2;
    public static final int MAX_ATTEMPTS = MAX_RETRIES + 1;

    /** Total DB attempts / client requests under burst. Above this we are amplifying load on the primary. */
    public static final double MAX_RETRY_AMPLIFICATION = 1.10;

    /** Capacity planning: hot slots see 20 concurrent requests at release time (drops, tournaments). */
    public static final int BURST_FACTOR = 20;

    /** Minimum replicas of reservation-service in production. */
    public static final int NODES = 3;

    private Constraints() {
    }

    public static double lockWaitLimitMillis() {
        return LOCK_WAIT_BUDGET.toMillis() + LOCK_WAIT_TOLERANCE_MILLIS;
    }
}

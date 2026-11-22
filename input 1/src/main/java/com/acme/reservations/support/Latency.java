package com.acme.reservations.support;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * Simulates network / IO latency. Every remote call in the system (DB round trip,
 * payment gateway, message broker) goes through here so timings are realistic
 * without needing real infrastructure.
 */
public final class Latency {

    private Latency() {
    }

    public static void parkMicros(long micros) {
        if (micros <= 0) {
            return;
        }
        long deadline = System.nanoTime() + micros * 1_000L;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    public static void park(Duration duration) {
        parkMicros(duration.toNanos() / 1_000L);
    }

    /** Parks for a uniformly random duration in [minMillis, maxMillis]. */
    public static void parkJittered(long minMillis, long maxMillis) {
        long micros = ThreadLocalRandom.current().nextLong(minMillis * 1_000L, maxMillis * 1_000L + 1);
        parkMicros(micros);
    }

    public static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}

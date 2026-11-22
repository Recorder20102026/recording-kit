package com.acme.reservations.service;

import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.support.Latency;

import java.time.Duration;

/** Retry settings for the reservation write path. */
public final class RetryPolicy {

    private final int maxRetries;
    private final Duration backoff;

    public RetryPolicy(int maxRetries, Duration backoff) {
        this.maxRetries = maxRetries;
        this.backoff = backoff;
    }

    public static RetryPolicy fromConfig(ReservationConfig config) {
        return new RetryPolicy(config.maxRetries(), config.retryBackoff());
    }

    /** @param attemptsSoFar number of attempts already made (1 after the first failure) */
    public boolean shouldRetry(int attemptsSoFar) {
        return attemptsSoFar <= maxRetries;
    }

    public void backoff(int attemptsSoFar) {
        Latency.park(backoff);
    }

    public int maxRetries() {
        return maxRetries;
    }
}

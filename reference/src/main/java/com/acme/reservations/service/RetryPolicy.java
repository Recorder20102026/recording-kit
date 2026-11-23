package com.acme.reservations.service;

import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.db.*;
import com.acme.reservations.domain.ReservationResult;
import com.acme.reservations.support.Latency;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

/** Bounded retry for the reservation write path (ADR-007). */
public final class RetryPolicy {

    /** SRE retry budget: at most 2 retries, whatever reservation.properties says. */
    public static final int HARD_MAX_RETRIES = 2;

    private final int maxRetries;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration clientRetryAfter;

    public RetryPolicy(int maxRetries, Duration baseBackoff, Duration maxBackoff, Duration clientRetryAfter) {
        this.maxRetries = Math.max(0, Math.min(maxRetries, HARD_MAX_RETRIES));
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.clientRetryAfter = clientRetryAfter;
    }

    public static RetryPolicy fromConfig(ReservationConfig config) {
        return new RetryPolicy(config.maxRetries(), config.retryBackoffBase(), config.retryBackoffMax(),
                config.clientRetryAfter());
    }

    /**
     * Runs attempt 1, 2, 3. Only lock_timeout (55P03) and transient DB errors are retried. A 409 Conflict is a
     * returned business answer, not an exception, so it is never retried: the slot will not get freer.
     */
    public ReservationResult execute(IntFunction<ReservationResult> attempt, Runnable onRetry) {
        for (int n = 1; ; n++) {
            try {
                return attempt.apply(n);
            } catch (LockWaitTimeoutException | TransientDbException e) {
                if (n > maxRetries) {
                    return new ReservationResult.Busy("LOCK_CONTENTION: " + e.getMessage(), clientRetryAfter, n);
                }
                onRetry.run();
                backoff(n);
            }
        }
    }

    /** Full jitter: sleep a random time in [0, min(max, base * 2^(n-1))], so contenders don't return in lock-step. */
    private void backoff(int attemptsSoFar) {
        long capMicros = Math.min(maxBackoff.toNanos() / 1_000, (baseBackoff.toNanos() / 1_000) << (attemptsSoFar - 1));
        Latency.parkMicros(ThreadLocalRandom.current().nextLong(capMicros + 1));
    }

    public int maxRetries() {
        return maxRetries;
    }
}

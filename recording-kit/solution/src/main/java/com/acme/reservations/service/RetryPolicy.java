package com.acme.reservations.service;

import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.db.*;
import com.acme.reservations.support.Latency;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.*;

/**
 * Bounded retry for the reservation write path.
 * <ul>
 *   <li>Retries ONLY lock timeouts and transient DB errors - never business outcomes (409, 400).</li>
 *   <li>At most {@value #HARD_MAX_RETRIES} retries, whatever the config says (SRE retry budget).</li>
 *   <li>Exponential backoff with full jitter, so contenders do not come back in lock-step.</li>
 *   <li>When the budget is spent the caller gets a value (503 + Retry-After), not an exception.</li>
 * </ul>
 */
public final class RetryPolicy {

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
     * Runs {@code attempt} (called with attempt number 1, 2, 3 ...) until it returns or the budget is spent.
     *
     * @param onRetry     called before every retry (metrics)
     * @param onExhausted maps the last failure and the number of attempts made to a result
     */
    public <T> T execute(IntFunction<T> attempt, Runnable onRetry, BiFunction<RuntimeException, Integer, T> onExhausted) {
        for (int n = 1; ; n++) {
            try {
                return attempt.apply(n);
            } catch (LockWaitTimeoutException | TransientDbException e) {
                if (n > maxRetries) {
                    return onExhausted.apply(e, n);
                }
                onRetry.run();
                backoff(n);
            }
        }
    }

    /** Full jitter: sleep a uniform random time in [0, min(max, base * 2^(n-1))]. */
    private void backoff(int attemptsSoFar) {
        long capMicros = Math.min(maxBackoff.toNanos() / 1_000, (baseBackoff.toNanos() / 1_000) << (attemptsSoFar - 1));
        Latency.parkMicros(ThreadLocalRandom.current().nextLong(capMicros + 1));
    }

    public Duration retryAfter() {
        return clientRetryAfter;
    }

    public int maxRetries() {
        return maxRetries;
    }
}

package com.acme.reservations.service;

import java.util.concurrent.atomic.*;

/** Per-node counters (would be Micrometer meters in production). */
public final class ServiceMetrics {

    private final LongAdder createRequests = new LongAdder();
    private final LongAdder createAttempts = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final AtomicInteger maxAttemptsPerRequest = new AtomicInteger();

    public void recordCreate(int attempts) {
        createRequests.increment();
        createAttempts.add(attempts);
        maxAttemptsPerRequest.accumulateAndGet(attempts, Math::max);
    }

    public void recordRetry() {
        retries.increment();
    }

    public long createRequests() {
        return createRequests.sum();
    }

    public long createAttempts() {
        return createAttempts.sum();
    }

    public long retries() {
        return retries.sum();
    }

    public int maxAttemptsPerRequest() {
        return maxAttemptsPerRequest.get();
    }
}

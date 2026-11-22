package com.acme.reservations.sideeffects;

import com.acme.reservations.support.Latency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client for the external payment provider (simulated). Holds are real money on a customer's card -
 * an orphaned hold is a support ticket and a chargeback risk.
 *
 * <p>The provider de-duplicates on {@code idempotencyKey}: the same key always returns the same hold.
 * Calls take a few milliseconds and are NOT part of any database transaction.
 */
public final class PaymentGateway {

    private final long minLatencyMillis;
    private final long maxLatencyMillis;
    private final ConcurrentHashMap<String, PaymentHold> holdsByKey = new ConcurrentHashMap<>();
    private final AtomicLong holdIds = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();

    public PaymentGateway(long minLatencyMillis, long maxLatencyMillis) {
        this.minLatencyMillis = minLatencyMillis;
        this.maxLatencyMillis = maxLatencyMillis;
    }

    /** {@code POST /v2/holds} with {@code Idempotency-Key}. */
    public PaymentHold placeHold(String idempotencyKey, String reservationRef, String customerId, long amountCents) {
        calls.incrementAndGet();
        Latency.parkJittered(minLatencyMillis, maxLatencyMillis);
        return holdsByKey.computeIfAbsent(idempotencyKey, key -> new PaymentHold(
                "HOLD-" + holdIds.incrementAndGet(), key, reservationRef, customerId, amountCents,
                PaymentHold.Status.ACTIVE));
    }

    /** {@code POST /v2/holds/{key}/release} - idempotent. */
    public void releaseHold(String idempotencyKey) {
        calls.incrementAndGet();
        Latency.parkJittered(minLatencyMillis, maxLatencyMillis);
        holdsByKey.computeIfPresent(idempotencyKey, (key, hold) -> hold.released());
    }

    public List<PaymentHold> holds() {
        return new ArrayList<>(holdsByKey.values());
    }

    public long calls() {
        return calls.get();
    }
}

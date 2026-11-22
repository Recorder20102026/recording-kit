package com.acme.reservations.domain;

/**
 * Row of the {@code reservation} table.
 *
 * @param requestId      client-supplied idempotency key ({@code Idempotency-Key} header)
 * @param paymentHoldKey idempotency key used with the payment gateway for this booking's hold
 * @param version        optimistic-locking version, bumped on every update (cancel / reschedule)
 */
public record Reservation(String id,
                          String requestId,
                          String resourceId,
                          String customerId,
                          TimeInterval interval,
                          ReservationStatus status,
                          long amountCents,
                          String paymentHoldKey,
                          String createdByNode,
                          long version) {

    public boolean isActive() {
        return status == ReservationStatus.CONFIRMED;
    }

    public Reservation cancelled() {
        return new Reservation(id, requestId, resourceId, customerId, interval, ReservationStatus.CANCELLED,
                amountCents, paymentHoldKey, createdByNode, version + 1);
    }
}

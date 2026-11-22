package com.acme.reservations.outbox;

/**
 * Row of the {@code outbox} table (transactional outbox pattern, RES-318).
 * Written in the same DB transaction as the business change and dispatched by
 * {@link OutboxRelay} only after COMMIT, so a rolled-back transaction never leaks a side effect.
 *
 * @param idempotencyKey forwarded to the downstream system so redelivery is harmless
 */
public record OutboxMessage(String id,
                            long sequence,
                            Type type,
                            String reservationId,
                            String resourceId,
                            String customerId,
                            long amountCents,
                            String idempotencyKey,
                            Status status) {

    public enum Type {
        PLACE_PAYMENT_HOLD,
        RELEASE_PAYMENT_HOLD,
        RESERVATION_CREATED_EVENT,
        RESERVATION_CANCELLED_EVENT
    }

    public enum Status { PENDING, SENT }

    public static OutboxMessage pending(long sequence, Type type, String reservationId, String resourceId,
                                        String customerId, long amountCents, String idempotencyKey) {
        return new OutboxMessage("OBX-" + sequence, sequence, type, reservationId, resourceId, customerId,
                amountCents, idempotencyKey, Status.PENDING);
    }

    public OutboxMessage sent() {
        return new OutboxMessage(id, sequence, type, reservationId, resourceId, customerId, amountCents,
                idempotencyKey, Status.SENT);
    }
}

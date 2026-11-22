package com.acme.reservations.sideeffects;

/** Message on the {@code reservations.v1} topic, consumed by notifications, calendar sync and billing. */
public record ReservationEvent(String eventId, Type type, String reservationId, String resourceId, String customerId) {

    public enum Type { RESERVATION_CREATED, RESERVATION_CANCELLED }
}

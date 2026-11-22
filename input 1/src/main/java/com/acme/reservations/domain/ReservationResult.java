package com.acme.reservations.domain;

import java.time.Duration;

/** Outcome of a create-reservation call. {@code attempts} = number of DB transactions tried. */
public sealed interface ReservationResult {

    int attempts();

    /** 201 Created (or 200 OK when {@code replayed} - same Idempotency-Key seen before). */
    record Booked(Reservation reservation, int attempts, boolean replayed) implements ReservationResult {
    }

    /** 409 Conflict - the slot overlaps an existing confirmed reservation. */
    record Conflict(String resourceId, TimeInterval requested, String conflictingReservationId, int attempts)
            implements ReservationResult {
    }

    /** 400 / 404 - validation failure. */
    record Rejected(String reason, int attempts) implements ReservationResult {
    }

    /** 503 Service Unavailable + Retry-After - could not get a decision within the retry budget. */
    record Busy(String reason, Duration retryAfter, int attempts) implements ReservationResult {
    }
}

package com.acme.reservations.api;

import com.acme.reservations.domain.ReservationResult;

/**
 * What the API returns to the client.
 *
 * @param result the service result, or {@code null} if the request blew up with an exception (500)
 */
public record HttpResponse(int status, String body, ReservationResult result, String node) {

    public boolean isSuccess() {
        return status == 200 || status == 201;
    }

    public boolean isServerError() {
        return status >= 500;
    }

    public static HttpResponse from(ReservationResult result, String node) {
        return switch (result) {
            case ReservationResult.Booked b -> new HttpResponse(b.replayed() ? 200 : 201,
                    "{\"reservationId\":\"" + b.reservation().id() + "\"}", result, node);
            case ReservationResult.Conflict c -> new HttpResponse(409,
                    "{\"error\":\"SLOT_TAKEN\",\"conflictsWith\":\"" + c.conflictingReservationId() + "\"}",
                    result, node);
            case ReservationResult.Rejected r -> new HttpResponse(
                    "UNKNOWN_RESOURCE".equals(r.reason()) ? 404 : 400,
                    "{\"error\":\"" + r.reason() + "\"}", result, node);
            case ReservationResult.Busy b -> new HttpResponse(503,
                    "{\"error\":\"BUSY\",\"retryAfterMs\":" + b.retryAfter().toMillis() + "}", result, node);
        };
    }

    public static HttpResponse serverError(RuntimeException e, String node) {
        return new HttpResponse(500, "{\"error\":\"" + e.getClass().getSimpleName() + ": " + e.getMessage() + "\"}",
                null, node);
    }
}

package com.acme.reservations.api;

import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.service.ReservationService;

/**
 * HTTP adapter.
 * <pre>
 * POST   /v1/resources/{resourceId}/reservations   (Idempotency-Key header -> requestId)
 * DELETE /v1/reservations/{reservationId}
 * </pre>
 */
public final class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    public HttpResponse postReservation(CreateReservationRequest request) {
        try {
            return HttpResponse.from(service.createReservation(request), service.nodeName());
        } catch (RuntimeException e) {
            return HttpResponse.serverError(e, service.nodeName());
        }
    }

    public int deleteReservation(String reservationId) {
        return switch (service.cancelReservation(reservationId)) {
            case CANCELLED -> 204;
            case NOT_FOUND -> 404;
            case ALREADY_CANCELLED, CONCURRENT_MODIFICATION -> 409;
        };
    }
}

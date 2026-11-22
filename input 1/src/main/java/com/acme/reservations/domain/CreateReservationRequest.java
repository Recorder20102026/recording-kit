package com.acme.reservations.domain;

/**
 * Body of {@code POST /v1/resources/{resourceId}/reservations}.
 *
 * @param requestId value of the {@code Idempotency-Key} header; clients re-send the same key when they retry
 */
public record CreateReservationRequest(String requestId, String resourceId, String customerId, TimeInterval interval) {
}

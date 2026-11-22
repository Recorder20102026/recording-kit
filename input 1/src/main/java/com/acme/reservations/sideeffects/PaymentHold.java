package com.acme.reservations.sideeffects;

/** A pre-authorisation on the customer's card, held at the payment provider. */
public record PaymentHold(String holdId, String idempotencyKey, String reservationRef, String customerId,
                          long amountCents, Status status) {

    public enum Status { ACTIVE, RELEASED }

    public PaymentHold released() {
        return new PaymentHold(holdId, idempotencyKey, reservationRef, customerId, amountCents, Status.RELEASED);
    }
}

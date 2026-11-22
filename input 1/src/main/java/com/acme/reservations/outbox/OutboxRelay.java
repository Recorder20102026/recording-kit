package com.acme.reservations.outbox;

import com.acme.reservations.db.Database;
import com.acme.reservations.persistence.OutboxRepository;
import com.acme.reservations.sideeffects.EventBus;
import com.acme.reservations.sideeffects.PaymentGateway;
import com.acme.reservations.sideeffects.ReservationEvent;

import java.util.List;
import java.util.Optional;

/**
 * Background worker that dispatches committed outbox rows (at-least-once).
 * Every downstream call carries an idempotency key, so redelivery after a crash is harmless.
 *
 * <p>Introduced with RES-318 for the cancel path. The create path was supposed to follow.
 */
public final class OutboxRelay {

    private final Database db;
    private final OutboxRepository outbox;
    private final PaymentGateway paymentGateway;
    private final EventBus eventBus;

    public OutboxRelay(Database db, OutboxRepository outbox, PaymentGateway paymentGateway, EventBus eventBus) {
        this.db = db;
        this.outbox = outbox;
        this.paymentGateway = paymentGateway;
        this.eventBus = eventBus;
    }

    /** Dispatches everything currently pending. Returns the number of messages sent. */
    public int drain() {
        List<OutboxMessage> pending = db.inTransaction("outbox.scan", outbox::findPending);
        int sent = 0;
        for (OutboxMessage candidate : pending) {
            boolean dispatched = db.inTransaction("outbox.relay", tx -> {
                Optional<OutboxMessage> locked = outbox.lock(tx, candidate.id());
                if (locked.isEmpty() || locked.get().status() != OutboxMessage.Status.PENDING) {
                    return false;
                }
                dispatch(locked.get());
                outbox.markSent(tx, candidate.id());
                return true;
            });
            if (dispatched) {
                sent++;
            }
        }
        return sent;
    }

    private void dispatch(OutboxMessage m) {
        switch (m.type()) {
            case PLACE_PAYMENT_HOLD ->
                    paymentGateway.placeHold(m.idempotencyKey(), m.reservationId(), m.customerId(), m.amountCents());
            case RELEASE_PAYMENT_HOLD -> paymentGateway.releaseHold(m.idempotencyKey());
            case RESERVATION_CREATED_EVENT -> eventBus.publish(new ReservationEvent(m.idempotencyKey(),
                    ReservationEvent.Type.RESERVATION_CREATED, m.reservationId(), m.resourceId(), m.customerId()));
            case RESERVATION_CANCELLED_EVENT -> eventBus.publish(new ReservationEvent(m.idempotencyKey(),
                    ReservationEvent.Type.RESERVATION_CANCELLED, m.reservationId(), m.resourceId(), m.customerId()));
        }
    }
}

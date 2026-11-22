package com.acme.reservations.evaluation.spikes;

import com.acme.reservations.db.Database;
import com.acme.reservations.db.LockWaitTimeoutException;
import com.acme.reservations.db.Transaction;
import com.acme.reservations.db.TransientDbException;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.domain.ReservationResult;
import com.acme.reservations.domain.ReservationStatus;
import com.acme.reservations.outbox.OutboxMessage;
import com.acme.reservations.persistence.OutboxRepository;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.service.ServiceMetrics;
import com.acme.reservations.support.Latency;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** Shared plumbing for the spikes so that only the locking strategy differs between them. */
final class SpikeSupport {

    static final int MAX_RETRIES = 2;
    static final long BACKOFF_BASE_MICROS = 2_000;
    static final long BACKOFF_CAP_MICROS = 20_000;

    private SpikeSupport() {
    }

    /** Bounded retry: max 2 retries on lock timeout / transient errors, exponential backoff with full jitter. */
    static ReservationResult withBoundedRetry(Database db, ServiceMetrics metrics, String label,
                                              Function<Transaction, AttemptBody> body) {
        for (int attempt = 1; ; attempt++) {
            final int n = attempt;
            try {
                ReservationResult result = db.inTransaction(label, tx -> body.apply(tx).run(n));
                metrics.recordCreate(n);
                return result;
            } catch (LockWaitTimeoutException | TransientDbException e) {
                if (attempt > MAX_RETRIES) {
                    metrics.recordCreate(n);
                    return new ReservationResult.Busy(e.getMessage(), Duration.ofMillis(100), n);
                }
                metrics.recordRetry();
                long cap = Math.min(BACKOFF_CAP_MICROS, BACKOFF_BASE_MICROS << (attempt - 1));
                Latency.parkMicros(ThreadLocalRandom.current().nextLong(cap + 1));
            }
        }
    }

    @FunctionalInterface
    interface AttemptBody {
        ReservationResult run(int attempt);
    }

    /**
     * Same pre-lock reads as the production path: idempotency lookup + resource lookup.
     * Returns a replay result if this request was already booked.
     */
    static Optional<ReservationResult> preamble(Transaction tx, CreateReservationRequest r, int attempt) {
        Optional<Reservation> existing = tx.select(Schema.RESERVATIONS, "R-" + r.requestId());
        tx.select(Schema.RESOURCES, r.resourceId());
        return existing.map(e -> new ReservationResult.Booked(e, attempt, true));
    }

    /** Correct half-open overlap check (non-locking read). */
    static Optional<Reservation> findConflict(Transaction tx, CreateReservationRequest r) {
        return tx.selectWhere(Schema.RESERVATIONS, e -> e.resourceId().equals(r.resourceId()) && e.isActive()
                && e.interval().overlaps(r.interval())).stream().findFirst();
    }

    static ReservationResult conflict(CreateReservationRequest r, Reservation clash, int attempt) {
        return new ReservationResult.Conflict(r.resourceId(), r.interval(), clash.id(), attempt);
    }

    /** INSERT reservation + two outbox rows - identical write set for every design. */
    static ReservationResult insertBooking(Transaction tx, Database db, CreateReservationRequest r, String node,
                                           int attempt) {
        Reservation reservation = new Reservation("R-" + r.requestId(), r.requestId(), r.resourceId(),
                r.customerId(), r.interval(), ReservationStatus.CONFIRMED, 4_000, "R-" + r.requestId(), node, 1);
        tx.insert(Schema.RESERVATIONS, reservation.id(), reservation);
        OutboxRepository outbox = new OutboxRepository(db);
        outbox.enqueue(tx, OutboxMessage.Type.PLACE_PAYMENT_HOLD, reservation.id(), r.resourceId(), r.customerId(),
                4_000, reservation.id());
        outbox.enqueue(tx, OutboxMessage.Type.RESERVATION_CREATED_EVENT, reservation.id(), r.resourceId(),
                r.customerId(), 0, "created:" + reservation.id());
        return new ReservationResult.Booked(reservation, attempt, false);
    }
}

package com.acme.reservations.service;

import com.acme.reservations.db.*;
import com.acme.reservations.domain.*;
import com.acme.reservations.outbox.OutboxMessage;
import com.acme.reservations.persistence.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Reservation write path. One instance per service node (pod); holds NO cross-request state -
 * every coordination decision is made in the shared database by the configured {@link LockingStrategy}
 * ({@code reservation.locking.strategy}, ADR-007).
 */
public final class ReservationService {

    static final Duration MAX_BOOKING_LENGTH = Duration.ofHours(8);

    private final String nodeName;
    private final Database db;
    private final ReservationRepository reservations;
    private final ResourceRepository resources;
    private final OutboxRepository outbox;
    private final SlotAvailabilityChecker availability;
    private final LockingStrategy locking;
    private final RetryPolicy retryPolicy;
    private final ServiceMetrics metrics;

    public ReservationService(String nodeName,
                              Database db,
                              ReservationRepository reservations,
                              ResourceRepository resources,
                              OutboxRepository outbox,
                              SlotAvailabilityChecker availability,
                              LockingStrategy locking,
                              RetryPolicy retryPolicy,
                              ServiceMetrics metrics) {
        this.nodeName = nodeName;
        this.db = db;
        this.reservations = reservations;
        this.resources = resources;
        this.outbox = outbox;
        this.availability = availability;
        this.locking = locking;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ create

    public ReservationResult createReservation(CreateReservationRequest request) {
        Optional<ReservationResult.Rejected> invalid = validate(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        String reservationId = reservationIdFor(request);

        ReservationResult result = retryPolicy.execute(
                attempt -> db.inTransaction("reservation.create",
                        tx -> createInTransaction(tx, request, reservationId, attempt)),
                metrics::recordRetry,
                (failure, attempts) -> new ReservationResult.Busy("LOCK_CONTENTION: " + failure.getMessage(),
                        retryPolicy.retryAfter(), attempts));

        metrics.recordCreate(result.attempts());
        return result;
    }

    private ReservationResult createInTransaction(Transaction tx, CreateReservationRequest request,
                                                  String reservationId, int attempt) {
        // 0. Idempotent replay (client retry, lost COMMIT ack, LB re-route): hand back what we already booked.
        Optional<Reservation> existing = reservations.findById(tx, reservationId);
        if (existing.isPresent()) {
            return replay(existing.get(), request, attempt);
        }

        Optional<Resource> resource = resources.findById(tx, request.resourceId());
        if (resource.isEmpty() || !resource.get().active()) {
            return new ReservationResult.Rejected("UNKNOWN_RESOURCE", attempt);
        }

        // 1. Fast path, no locks: most burst losers are rejected here once the winner has committed.
        Optional<Reservation> committedClash = availability.findConflict(tx, request.resourceId(), request.interval());
        if (committedClash.isPresent()) {
            return new ReservationResult.Conflict(request.resourceId(), request.interval(),
                    committedClash.get().id(), attempt);
        }

        // 2. Locking strategy: take the DB locks for this interval (every wait capped by lock_timeout = 50ms).
        LockingStrategy.Lease lease = locking.acquire(tx, request.resourceId(), request.interval());

        // 3. Authoritative overlap check, evaluated while we hold the locks.
        Optional<String> clash = lease.clash();
        if (clash.isPresent()) {
            if (clash.get().equals(reservationId)) {
                // Our own retry won the race on another pod a moment ago.
                return replay(reservations.findById(tx, reservationId).orElseThrow(), request, attempt);
            }
            return new ReservationResult.Conflict(request.resourceId(), request.interval(), clash.get(), attempt);
        }

        // 4. Conditional insert (PK derived from the Idempotency-Key) + the strategy's claim
        //    (fenced: version-guarded UPDATE on every bucket; pessimistic: nothing extra).
        long amount = resource.get().priceFor(request.interval());
        Reservation reservation = new Reservation(reservationId, request.requestId(), request.resourceId(),
                request.customerId(), request.interval(), ReservationStatus.CONFIRMED, amount,
                reservationId, nodeName, 1);
        reservations.insert(tx, reservation);
        lease.claim(tx, reservationId);

        // 5. Side effects go to the outbox in the SAME transaction; OutboxRelay delivers them after COMMIT.
        //    Rolled back => nothing was ever sent. Retried => same idempotency keys, nothing sent twice.
        outbox.enqueue(tx, OutboxMessage.Type.PLACE_PAYMENT_HOLD, reservationId, request.resourceId(),
                request.customerId(), amount, reservation.paymentHoldKey());
        outbox.enqueue(tx, OutboxMessage.Type.RESERVATION_CREATED_EVENT, reservationId, request.resourceId(),
                request.customerId(), 0, "created:" + reservationId);

        return new ReservationResult.Booked(reservation, attempt, false);
    }

    private ReservationResult replay(Reservation existing, CreateReservationRequest request, int attempt) {
        boolean samePayload = existing.resourceId().equals(request.resourceId())
                && existing.interval().equals(request.interval())
                && existing.customerId().equals(request.customerId());
        if (!samePayload) {
            return new ReservationResult.Rejected("IDEMPOTENCY_KEY_REUSED", attempt);
        }
        return new ReservationResult.Booked(existing, attempt, true);
    }

    /** Same customer + same Idempotency-Key always maps to the same primary key. */
    static String reservationIdFor(CreateReservationRequest request) {
        String scoped = request.customerId() + "|" + request.requestId();
        return "R-" + UUID.nameUUIDFromBytes(scoped.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ cancel

    public enum CancelOutcome { CANCELLED, NOT_FOUND, ALREADY_CANCELLED, CONCURRENT_MODIFICATION }

    /** Cancel path - transactional outbox (RES-318) + releases whatever the locking strategy claimed. */
    public CancelOutcome cancelReservation(String reservationId) {
        return db.inTransaction("reservation.cancel", tx -> {
            Optional<Reservation> found = reservations.findByIdForUpdate(tx, reservationId);
            if (found.isEmpty()) {
                return CancelOutcome.NOT_FOUND;
            }
            Reservation reservation = found.get();
            if (!reservation.isActive()) {
                return CancelOutcome.ALREADY_CANCELLED;
            }
            if (!reservations.markCancelled(tx, reservation)) {
                return CancelOutcome.CONCURRENT_MODIFICATION;
            }
            locking.release(tx, reservation);
            outbox.enqueue(tx, OutboxMessage.Type.RELEASE_PAYMENT_HOLD, reservation.id(), reservation.resourceId(),
                    reservation.customerId(), reservation.amountCents(), reservation.paymentHoldKey());
            outbox.enqueue(tx, OutboxMessage.Type.RESERVATION_CANCELLED_EVENT, reservation.id(),
                    reservation.resourceId(), reservation.customerId(), 0, "cancelled:" + reservation.id());
            return CancelOutcome.CANCELLED;
        });
    }

    // -------------------------------------------------------------- validation

    private Optional<ReservationResult.Rejected> validate(CreateReservationRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            return Optional.of(new ReservationResult.Rejected("MISSING_IDEMPOTENCY_KEY", 0));
        }
        if (!request.interval().isValid()) {
            return Optional.of(new ReservationResult.Rejected("INVALID_INTERVAL", 0));
        }
        if (request.interval().duration().compareTo(MAX_BOOKING_LENGTH) > 0) {
            return Optional.of(new ReservationResult.Rejected("BOOKING_TOO_LONG", 0));
        }
        return Optional.empty();
    }

    public String nodeName() {
        return nodeName;
    }
}

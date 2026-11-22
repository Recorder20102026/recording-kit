package com.acme.reservations.service;

import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.db.Database;
import com.acme.reservations.db.LockWaitTimeoutException;
import com.acme.reservations.db.Transaction;
import com.acme.reservations.db.TransientDbException;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.domain.ReservationResult;
import com.acme.reservations.domain.ReservationStatus;
import com.acme.reservations.domain.Resource;
import com.acme.reservations.outbox.OutboxMessage;
import com.acme.reservations.persistence.OutboxRepository;
import com.acme.reservations.persistence.ReservationRepository;
import com.acme.reservations.persistence.ResourceRepository;
import com.acme.reservations.sideeffects.EventBus;
import com.acme.reservations.sideeffects.PaymentGateway;
import com.acme.reservations.sideeffects.PaymentHold;
import com.acme.reservations.sideeffects.ReservationEvent;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reservation write path. One instance per service node (pod).
 */
public final class ReservationService {

    static final Duration MAX_BOOKING_LENGTH = Duration.ofHours(8);

    private final String nodeName;
    private final Database db;
    private final ReservationRepository reservations;
    private final ResourceRepository resources;
    private final OutboxRepository outbox;
    private final SlotAvailabilityChecker availability;
    private final PaymentGateway paymentGateway;
    private final EventBus eventBus;
    private final RetryPolicy retryPolicy;
    private final ReservationConfig config;
    private final ServiceMetrics metrics;

    // RES-212 (hotfix): serialize bookings per resource so two requests can't both pass the
    // availability check. Fixed the double bookings we saw in staging.
    private final ConcurrentHashMap<String, ReentrantLock> resourceLocks = new ConcurrentHashMap<>();

    public ReservationService(String nodeName,
                              Database db,
                              ReservationRepository reservations,
                              ResourceRepository resources,
                              OutboxRepository outbox,
                              SlotAvailabilityChecker availability,
                              PaymentGateway paymentGateway,
                              EventBus eventBus,
                              RetryPolicy retryPolicy,
                              ReservationConfig config,
                              ServiceMetrics metrics) {
        this.nodeName = nodeName;
        this.db = db;
        this.reservations = reservations;
        this.resources = resources;
        this.outbox = outbox;
        this.availability = availability;
        this.paymentGateway = paymentGateway;
        this.eventBus = eventBus;
        this.retryPolicy = retryPolicy;
        this.config = config;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ create

    public ReservationResult createReservation(CreateReservationRequest request) {
        Optional<ReservationResult.Rejected> invalid = validate(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }

        int attempt = 0;
        try {
            while (true) {
                attempt++;
                final int currentAttempt = attempt;
                ReentrantLock localLock = config.localLockEnabled()
                        ? resourceLocks.computeIfAbsent(request.resourceId(), id -> new ReentrantLock())
                        : null;
                if (localLock != null) {
                    localLock.lock();
                }
                try {
                    return db.inTransaction("reservation.create",
                            tx -> createInTransaction(tx, request, currentAttempt));
                } catch (LockWaitTimeoutException | TransientDbException e) {
                    if (!retryPolicy.shouldRetry(attempt)) {
                        throw e;
                    }
                    metrics.recordRetry();
                    retryPolicy.backoff(attempt);
                } finally {
                    if (localLock != null) {
                        localLock.unlock();
                    }
                }
            }
        } finally {
            metrics.recordCreate(attempt);
        }
    }

    private ReservationResult createInTransaction(Transaction tx, CreateReservationRequest request, int attempt) {
        Optional<Resource> resource = resources.findById(tx, request.resourceId());
        if (resource.isEmpty() || !resource.get().active()) {
            return new ReservationResult.Rejected("UNKNOWN_RESOURCE", attempt);
        }

        // 1. Is the slot free?
        Optional<Reservation> clash = availability.findConflict(tx, request.resourceId(), request.interval());
        if (clash.isPresent()) {
            return new ReservationResult.Conflict(request.resourceId(), request.interval(), clash.get().id(), attempt);
        }

        String reservationId = "R-" + UUID.randomUUID();
        long amount = resource.get().priceFor(request.interval());

        // 2. Secure the money before we hand out the slot.
        String holdKey = UUID.randomUUID().toString();
        PaymentHold hold = paymentGateway.placeHold(holdKey, reservationId, request.customerId(), amount);

        // 3. Persist the reservation.
        Reservation reservation = new Reservation(reservationId, request.requestId(), request.resourceId(),
                request.customerId(), request.interval(), ReservationStatus.CONFIRMED, amount,
                hold.idempotencyKey(), nodeName, 1);
        reservations.insert(tx, reservation);

        // 4. Ops dashboard counter.
        resources.incrementBookingCount(tx, request.resourceId());

        // 5. Tell the world (notifications, calendar sync, billing).
        eventBus.publish(new ReservationEvent(UUID.randomUUID().toString(),
                ReservationEvent.Type.RESERVATION_CREATED, reservationId, request.resourceId(),
                request.customerId()));

        return new ReservationResult.Booked(reservation, attempt, false);
    }

    // ------------------------------------------------------------------ cancel

    public enum CancelOutcome { CANCELLED, NOT_FOUND, ALREADY_CANCELLED, CONCURRENT_MODIFICATION }

    /** Cancel path - already migrated to the transactional outbox (RES-318). */
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

package com.acme.reservations.service;

import com.acme.reservations.db.Transaction;
import com.acme.reservations.domain.*;
import com.acme.reservations.persistence.ReservationRepository;

import java.time.Instant;
import java.util.Optional;

/** Answers "is this resource free for this interval?" against the reservation table. */
public final class SlotAvailabilityChecker {

    private final ReservationRepository reservations;

    public SlotAvailabilityChecker(ReservationRepository reservations) {
        this.reservations = reservations;
    }

    /**
     * Returns an existing confirmed reservation that clashes with {@code requested}, if any.
     * <p>Plain (non-locking) read: sees committed rows only.
     */
    public Optional<Reservation> findConflict(Transaction tx, String resourceId, TimeInterval requested) {
        for (Reservation existing : reservations.findActiveByResource(tx, resourceId)) {
            // A booking clashes when it would start while another booking is still running.
            Instant requestedStart = requested.start();
            boolean startsDuringExisting = !requestedStart.isBefore(existing.interval().start())
                    && requestedStart.isBefore(existing.interval().end());
            if (startsDuringExisting) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    public boolean isAvailable(Transaction tx, String resourceId, TimeInterval requested) {
        return findConflict(tx, resourceId, requested).isEmpty();
    }
}

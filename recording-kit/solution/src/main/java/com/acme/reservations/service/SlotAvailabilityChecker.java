package com.acme.reservations.service;

import com.acme.reservations.db.Transaction;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.domain.TimeInterval;
import com.acme.reservations.persistence.ReservationRepository;

import java.util.Optional;

/** Answers "is this resource free for this interval?" against the reservation table. */
public final class SlotAvailabilityChecker {

    private final ReservationRepository reservations;

    public SlotAvailabilityChecker(ReservationRepository reservations) {
        this.reservations = reservations;
    }

    /**
     * Returns an existing confirmed reservation that clashes with {@code requested}, if any.
     * <p>Plain (non-locking) read: sees committed rows only. Used as the cheap fast path; the
     * authoritative decision is taken under the slot-bucket fence (see {@link SlotBucketFence}).
     */
    public Optional<Reservation> findConflict(Transaction tx, String resourceId, TimeInterval requested) {
        for (Reservation existing : reservations.findActiveByResource(tx, resourceId)) {
            // Half-open overlap: catches left/right partial, contained, enclosing and identical intervals.
            if (existing.interval().overlaps(requested)) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    public boolean isAvailable(Transaction tx, String resourceId, TimeInterval requested) {
        return findConflict(tx, resourceId, requested).isEmpty();
    }
}

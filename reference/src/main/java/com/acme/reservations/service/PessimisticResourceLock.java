package com.acme.reservations.service;

import com.acme.reservations.db.Transaction;
import com.acme.reservations.domain.*;
import com.acme.reservations.persistence.ResourceRepository;

import java.util.Optional;

/**
 * Candidate 1 - pessimistic, resource-scoped locking.
 * <pre>
 *   SELECT * FROM resource WHERE id = :resourceId FOR UPDATE;   -- waits at most lock_timeout (50ms)
 *   -- re-check overlap on reservation (READ COMMITTED sees the previous holder's commit)
 *   INSERT INTO reservation ...; COMMIT;                        -- COMMIT releases the row lock
 * </pre>
 * <ul>
 *   <li>Multi-node: the lock is a row lock in the shared database, so it works across pods.</li>
 *   <li>Non-existent rows: the parent resource row always exists, so there is always something to lock,
 *       even when the competing reservation has not been written yet.</li>
 *   <li>Cost: EVERY booking of the resource queues on one row, including bookings for unrelated times.</li>
 * </ul>
 */
public final class PessimisticResourceLock implements LockingStrategy {

    private final ResourceRepository resources;
    private final SlotAvailabilityChecker availability;

    public PessimisticResourceLock(ResourceRepository resources, SlotAvailabilityChecker availability) {
        this.resources = resources;
        this.availability = availability;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public Lease acquire(Transaction tx, String resourceId, TimeInterval interval) {
        resources.lockById(tx, resourceId);
        Optional<String> clash = availability.findConflict(tx, resourceId, interval).map(Reservation::id);
        return new Lease() {
            @Override
            public Optional<String> clash() {
                return clash;
            }

            @Override
            public void claim(Transaction tx, String reservationId) {
                // Nothing to record: the inserted reservation row is what the next lock holder re-checks.
            }
        };
    }

    @Override
    public void release(Transaction tx, Reservation reservation) {
        // Nothing to undo: the row lock is released by COMMIT, and the cancelled row stops matching findConflict.
    }
}

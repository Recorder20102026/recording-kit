package com.acme.reservations.service;

import com.acme.reservations.db.Transaction;
import com.acme.reservations.domain.*;

import java.util.Optional;

/**
 * Concurrency control for the create-reservation critical section. Selected per deployment with
 * {@code reservation.locking.strategy} in {@code config/reservation.properties}.
 *
 * <p>Every implementation must keep its locks in the shared database (never in the JVM), because
 * production runs several pods. Every lock wait is bounded by the DB {@code lock_timeout} (50ms);
 * a timeout surfaces as {@link com.acme.reservations.db.LockWaitTimeoutException} and is handled by
 * {@link RetryPolicy}.
 */
public interface LockingStrategy {

    /** Value of {@code reservation.locking.strategy} that selects this implementation. */
    String name();

    /**
     * Takes this strategy's locks for {@code interval} on {@code resourceId} inside {@code tx}.
     * Locks are held until the transaction commits or rolls back.
     */
    Lease acquire(Transaction tx, String resourceId, TimeInterval interval);

    /** Cancel path: undo whatever {@link Lease#claim} recorded for {@code reservation}. */
    void release(Transaction tx, Reservation reservation);

    /** What the acquired locks protect, for the rest of the transaction. */
    interface Lease {

        /** Id of a confirmed reservation that overlaps the requested interval, as seen under the locks. */
        Optional<String> clash();

        /** Called right after the reservation row is inserted, still inside the transaction. */
        void claim(Transaction tx, String reservationId);
    }
}

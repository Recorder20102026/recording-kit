package com.acme.reservations.persistence;

import com.acme.reservations.db.Transaction;
import com.acme.reservations.domain.Reservation;

import java.util.List;
import java.util.Optional;

public final class ReservationRepository {

    /** {@code INSERT INTO reservation VALUES (...)} */
    public void insert(Transaction tx, Reservation reservation) {
        tx.insert(Schema.RESERVATIONS, reservation.id(), reservation);
    }

    /** {@code SELECT * FROM reservation WHERE id = ?} */
    public Optional<Reservation> findById(Transaction tx, String reservationId) {
        return tx.select(Schema.RESERVATIONS, reservationId);
    }

    /** {@code SELECT * FROM reservation WHERE id = ? FOR UPDATE} */
    public Optional<Reservation> findByIdForUpdate(Transaction tx, String reservationId) {
        return tx.selectForUpdate(Schema.RESERVATIONS, reservationId);
    }

    /** {@code SELECT * FROM reservation WHERE resource_id = ? AND status = 'CONFIRMED'} (non-locking) */
    public List<Reservation> findActiveByResource(Transaction tx, String resourceId) {
        return tx.selectWhere(Schema.RESERVATIONS, r -> r.resourceId().equals(resourceId) && r.isActive());
    }

    /** {@code UPDATE reservation SET status='CANCELLED', version=version+1 WHERE id=? AND version=?} */
    public boolean markCancelled(Transaction tx, Reservation reservation) {
        return tx.updateIfVersion(Schema.RESERVATIONS, reservation.id(), reservation.version(),
                Reservation::version, Reservation::cancelled);
    }
}

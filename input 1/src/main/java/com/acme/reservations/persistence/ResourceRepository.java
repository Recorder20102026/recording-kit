package com.acme.reservations.persistence;

import com.acme.reservations.db.Transaction;
import com.acme.reservations.domain.Resource;

import java.util.Optional;

public final class ResourceRepository {

    /** {@code SELECT * FROM resource WHERE id = ?} */
    public Optional<Resource> findById(Transaction tx, String resourceId) {
        return tx.select(Schema.RESOURCES, resourceId);
    }

    /** {@code SELECT * FROM resource WHERE id = ? FOR UPDATE} */
    public Optional<Resource> lockById(Transaction tx, String resourceId) {
        return tx.selectForUpdate(Schema.RESOURCES, resourceId);
    }

    /** {@code UPDATE resource SET booking_count = booking_count + 1, version = version + 1 WHERE id = ?} */
    public void incrementBookingCount(Transaction tx, String resourceId) {
        tx.update(Schema.RESOURCES, resourceId, Resource::withBookingRecorded);
    }
}

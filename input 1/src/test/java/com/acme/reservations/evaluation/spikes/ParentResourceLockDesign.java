package com.acme.reservations.evaluation.spikes;

import com.acme.reservations.db.Database;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.service.ServiceMetrics;

import java.util.Optional;

/**
 * Design C1 - pessimistic serialization on the parent row:
 * <pre>
 *   SELECT * FROM resource WHERE id = :resourceId FOR UPDATE;   -- every booking of the court queues here
 *   -- re-check overlap, INSERT reservation, INSERT outbox, COMMIT
 * </pre>
 * Correct across pods (the lock lives in the DB) and for partial overlaps (the parent row always exists),
 * but ALL bookings of a court - even ones for unrelated times - wait behind each other.
 */
public final class ParentResourceLockDesign implements LockingDesign {

    @Override
    public String code() {
        return "C1";
    }

    @Override
    public String title() {
        return "parent-resource SELECT ... FOR UPDATE";
    }

    @Override
    public String constraintUnderTest() {
        return "(c) 50ms lock wait + 2 retries under 20x burst";
    }

    @Override
    public Pod newPod(String nodeName, Database db, ServiceMetrics metrics) {
        return request -> SpikeSupport.withBoundedRetry(db, metrics, "spike.C1", tx -> attempt -> {
            Optional<com.acme.reservations.domain.ReservationResult> replay = SpikeSupport.preamble(tx, request, attempt);
            if (replay.isPresent()) {
                return replay.get();
            }
            Optional<Reservation> fastPath = SpikeSupport.findConflict(tx, request);
            if (fastPath.isPresent()) {
                return SpikeSupport.conflict(request, fastPath.get(), attempt);
            }
            tx.selectForUpdate(Schema.RESOURCES, request.resourceId());
            Optional<Reservation> clash = SpikeSupport.findConflict(tx, request);
            if (clash.isPresent()) {
                return SpikeSupport.conflict(request, clash.get(), attempt);
            }
            return SpikeSupport.insertBooking(tx, db, request, nodeName, attempt);
        });
    }
}

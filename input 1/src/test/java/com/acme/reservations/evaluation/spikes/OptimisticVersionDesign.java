package com.acme.reservations.evaluation.spikes;

import com.acme.reservations.db.Database;
import com.acme.reservations.db.DuplicateKeyException;
import com.acme.reservations.db.TableDef;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.service.ServiceMetrics;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Design B - row-level optimistic concurrency, the "obvious" DB-only fix:
 * <ul>
 *   <li>re-validate the {@code version} of every reservation row we read (CAS at commit), and</li>
 *   <li>{@code UNIQUE (resource_id, starts_at)} so two identical bookings collide on insert.</li>
 * </ul>
 * Versions can only protect rows that EXIST. A partially overlapping booking is a brand-new row with a
 * different start, so there is nothing to version-check and nothing unique to collide on.
 */
public final class OptimisticVersionDesign implements LockingDesign {

    /** {@code CREATE UNIQUE INDEX ON reservation (resource_id, starts_at)} modelled as its own key space. */
    private static final TableDef<String> START_KEY = new TableDef<>("spike_b_reservation_start_uq");

    @Override
    public String code() {
        return "B";
    }

    @Override
    public String title() {
        return "optimistic row version + UNIQUE(resource,start)";
    }

    @Override
    public String constraintUnderTest() {
        return "(b) partial overlap across non-existent rows";
    }

    @Override
    public Pod newPod(String nodeName, Database db, ServiceMetrics metrics) {
        return request -> SpikeSupport.withBoundedRetry(db, metrics, "spike.B", tx -> attempt -> {
            Optional<com.acme.reservations.domain.ReservationResult> replay = SpikeSupport.preamble(tx, request, attempt);
            if (replay.isPresent()) {
                return replay.get();
            }
            List<Reservation> seen = tx.selectWhere(com.acme.reservations.persistence.Schema.RESERVATIONS,
                    r -> r.resourceId().equals(request.resourceId()) && r.isActive());
            Optional<Reservation> clash = seen.stream().filter(r -> r.interval().overlaps(request.interval())).findFirst();
            if (clash.isPresent()) {
                return SpikeSupport.conflict(request, clash.get(), attempt);
            }
            try {
                tx.insert(START_KEY, request.resourceId() + "@" + request.interval().start(), request.requestId());
            } catch (DuplicateKeyException e) {
                return new com.acme.reservations.domain.ReservationResult.Conflict(request.resourceId(),
                        request.interval(), "unique(resource,start)", attempt);
            }
            // Version guard on the overlapping rows we read. There are none: the rival booking is a row that
            // did not exist when we read, so there is no version to compare and the CAS is a no-op.
            Map<String, Long> versions = seen.stream().filter(r -> r.interval().overlaps(request.interval()))
                    .collect(Collectors.toMap(Reservation::id, Reservation::version));
            tx.updateWhereVersion(com.acme.reservations.persistence.Schema.RESERVATIONS, versions,
                    Reservation::version, r -> r);
            return SpikeSupport.insertBooking(tx, db, request, nodeName, attempt);
        });
    }
}

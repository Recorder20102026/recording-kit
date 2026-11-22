package com.acme.reservations.evaluation.spikes;

import com.acme.reservations.db.Database;
import com.acme.reservations.db.TableDef;
import com.acme.reservations.db.TransientDbException;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.domain.ReservationResult;
import com.acme.reservations.domain.TimeInterval;
import com.acme.reservations.service.ServiceMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Design C2 - scoped key fencing on fixed-size time buckets:
 * <pre>
 *   -- 1. materialise + lock ONLY the 15-min buckets the interval touches, in sorted order, in one
 *   --    server-side call that stops at the first bucket already holding an overlapping booking
 *   SELECT * FROM lock_rows_until('slot_bucket', :bucket_keys, :overlaps_me);    -- upsert + row locks
 *   -- 2. authoritative overlap check on the RETURNED occupants (no extra query while holding the fence)
 *   -- 3. INSERT reservation (PK derived from Idempotency-Key = conditional insert)
 *   -- 4. UPDATE slot_bucket SET occupants = occupants || :me, version = version + 1
 *        WHERE (pk, version) IN (...)                                                   -- fencing guard
 * </pre>
 * Any two overlapping intervals share at least one bucket, so they serialize; non-overlapping
 * requests for the same court touch disjoint buckets and run in parallel.
 */
public final class SlotBucketFenceDesign implements LockingDesign {

    record Occupant(String reservationId, TimeInterval interval) {
    }

    record Bucket(String key, long version, List<Occupant> occupants) {
        Bucket with(Occupant o) {
            List<Occupant> next = new ArrayList<>(occupants);
            next.add(o);
            return new Bucket(key, version + 1, List.copyOf(next));
        }
    }

    private static final TableDef<Bucket> BUCKETS = new TableDef<>("spike_c2_slot_bucket");
    private static final Duration BUCKET = Duration.ofMinutes(15);

    @Override
    public String code() {
        return "C2";
    }

    @Override
    public String title() {
        return "slot-bucket fencing + version guard";
    }

    @Override
    public String constraintUnderTest() {
        return "(a) + (b) + (c)";
    }

    @Override
    public Pod newPod(String nodeName, Database db, ServiceMetrics metrics) {
        return request -> SpikeSupport.withBoundedRetry(db, metrics, "spike.C2", tx -> attempt -> {
            Optional<com.acme.reservations.domain.ReservationResult> replay = SpikeSupport.preamble(tx, request, attempt);
            if (replay.isPresent()) {
                return replay.get();
            }
            Optional<Reservation> fastPath = SpikeSupport.findConflict(tx, request);
            if (fastPath.isPresent()) {
                return SpikeSupport.conflict(request, fastPath.get(), attempt);
            }
            List<Bucket> fence = tx.upsertAllForUpdate(BUCKETS, bucketKeys(request.resourceId(), request.interval()),
                    key -> new Bucket(key, 0, List.of()),
                    b -> b.occupants().stream().anyMatch(o -> o.interval().overlaps(request.interval())));
            Optional<Occupant> clash = fence.stream().flatMap(b -> b.occupants().stream())
                    .filter(o -> o.interval().overlaps(request.interval())).findFirst();
            if (clash.isPresent()) {
                return new ReservationResult.Conflict(request.resourceId(), request.interval(),
                        clash.get().reservationId(), attempt);
            }
            ReservationResult booked = SpikeSupport.insertBooking(tx, db, request, nodeName, attempt);
            Occupant me = new Occupant("R-" + request.requestId(), request.interval());
            Map<String, Long> expected = new LinkedHashMap<>();
            fence.forEach(b -> expected.put(b.key(), b.version()));
            if (tx.updateWhereVersion(BUCKETS, expected, Bucket::version, b -> b.with(me)) != expected.size()) {
                throw new TransientDbException("fencing token moved");
            }
            return booked;
        });
    }

    static List<String> bucketKeys(String resourceId, TimeInterval interval) {
        long size = BUCKET.getSeconds();
        long first = Math.floorDiv(interval.start().getEpochSecond(), size);
        long last = Math.floorDiv(interval.end().getEpochSecond() - 1, size);
        List<String> keys = new ArrayList<>();
        for (long b = first; b <= last; b++) {
            keys.add(resourceId + "@" + Instant.ofEpochSecond(b * size));
        }
        return keys;
    }
}

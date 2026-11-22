package com.acme.reservations.service;

import com.acme.reservations.db.*;
import com.acme.reservations.domain.*;
import com.acme.reservations.persistence.*;

import java.time.*;
import java.util.*;

/**
 * Scoped key fencing for the reservation write path (ADR-007).
 *
 * <p>Time is cut into fixed buckets per resource (15 min by default). A booking locks exactly the buckets
 * its interval touches. Two intervals that overlap always share at least one bucket, so they serialize on
 * that row; bookings for other times on the same resource touch other rows and run in parallel.
 *
 * <ul>
 *   <li><b>Multi-node:</b> the locks are rows in the shared database, visible to every pod.</li>
 *   <li><b>Non-existent rows:</b> bucket rows are upserted on demand, so there is always a row to lock,
 *       even when the competing reservation has not been written yet.</li>
 *   <li><b>Deadlock-free:</b> keys are always locked in ascending time order.</li>
 *   <li><b>Bounded waits:</b> every lock wait is capped by the DB {@code lock_timeout} (50ms); a doomed
 *       request stops at the first bucket that already holds an overlapping booking and releases at once.</li>
 *   <li><b>Version guard:</b> claims/releases are {@code UPDATE ... WHERE version = :seen}; if a token moved,
 *       the transaction aborts instead of silently overwriting.</li>
 * </ul>
 */
public final class SlotBucketFence {

    private final Duration bucketSize;

    public SlotBucketFence(Duration bucketSize) {
        if (bucketSize.isZero() || bucketSize.isNegative() || Duration.ofDays(1).toSeconds() % bucketSize.toSeconds() != 0) {
            throw new IllegalArgumentException("bucket size must divide a day evenly: " + bucketSize);
        }
        this.bucketSize = bucketSize;
    }

    /** Sorted keys of every bucket that {@code [start, end)} touches. */
    public List<String> bucketKeys(String resourceId, TimeInterval interval) {
        long size = bucketSize.toSeconds();
        long first = Math.floorDiv(interval.start().getEpochSecond(), size);
        long last = Math.floorDiv(interval.end().getEpochSecond() - 1, size);
        List<String> keys = new ArrayList<>((int) (last - first + 1));
        for (long b = first; b <= last; b++) {
            keys.add(key(resourceId, Instant.ofEpochSecond(b * size)));
        }
        return keys;
    }

    /**
     * Upserts and locks the buckets of {@code interval} in order (one round trip). Stops right after the
     * first bucket that already holds an overlapping booking - that request is going to get a 409 anyway.
     */
    public Fence acquire(Transaction tx, String resourceId, TimeInterval interval) {
        List<SlotBucket> locked = tx.upsertAllForUpdate(Schema.SLOT_BUCKETS, bucketKeys(resourceId, interval),
                key -> SlotBucket.empty(key, resourceId, bucketStartOf(key)),
                bucket -> bucket.clashWith(interval).isPresent());
        return new Fence(interval, locked);
    }

    /** Cancel path: remove the reservation from every bucket it occupied (version-guarded). */
    public void release(Transaction tx, Reservation reservation) {
        List<SlotBucket> locked = tx.upsertAllForUpdate(Schema.SLOT_BUCKETS,
                bucketKeys(reservation.resourceId(), reservation.interval()),
                key -> SlotBucket.empty(key, reservation.resourceId(), bucketStartOf(key)));
        guardedUpdate(tx, locked, bucket -> bucket.withoutOccupant(reservation.id()));
    }

    /** The set of bucket rows this transaction holds locks on. */
    public final class Fence {

        private final TimeInterval interval;
        private final List<SlotBucket> buckets;

        private Fence(TimeInterval interval, List<SlotBucket> buckets) {
            this.interval = interval;
            this.buckets = buckets;
        }

        /** Authoritative overlap check, evaluated on the rows we hold locks on. */
        public Optional<SlotBucket.Occupant> clash() {
            return buckets.stream().map(b -> b.clashWith(interval)).flatMap(Optional::stream).findFirst();
        }

        /** Records the reservation in every bucket and advances each fencing token. */
        public void claim(Transaction tx, String reservationId) {
            SlotBucket.Occupant me = new SlotBucket.Occupant(reservationId, interval);
            guardedUpdate(tx, buckets, bucket -> bucket.withOccupant(me));
        }
    }

    private static void guardedUpdate(Transaction tx, List<SlotBucket> seen,
                                      java.util.function.UnaryOperator<SlotBucket> change) {
        Map<String, Long> expected = new LinkedHashMap<>();
        for (SlotBucket bucket : seen) {
            expected.put(bucket.key(), bucket.version());
        }
        int updated = tx.updateWhereVersion(Schema.SLOT_BUCKETS, expected, SlotBucket::version, change);
        if (updated != expected.size()) {
            throw new TransientDbException("40001 fencing token moved on slot_bucket " + expected.keySet());
        }
    }

    private static String key(String resourceId, Instant bucketStart) {
        return resourceId + "@" + bucketStart;
    }

    private static Instant bucketStartOf(String key) {
        return Instant.parse(key.substring(key.lastIndexOf('@') + 1));
    }
}

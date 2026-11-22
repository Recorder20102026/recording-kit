package com.acme.reservations.persistence;

import com.acme.reservations.domain.TimeInterval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Row of the {@code slot_bucket} table (ADR-007): one row per resource per fixed-size time bucket.
 * <pre>
 * CREATE TABLE slot_bucket (resource_id text, bucket_start timestamptz, version bigint,
 *                           occupants jsonb,             -- [{reservation_id, starts_at, ends_at}]
 *                           PRIMARY KEY (resource_id, bucket_start));
 * </pre>
 * The row is the lock target ("fence") for every booking that touches the bucket, so it gives us a
 * row to lock even when the competing reservation does not exist yet. {@code version} is the fencing
 * token: every claim / release bumps it and is applied with a version-guarded UPDATE.
 */
public record SlotBucket(String key, String resourceId, Instant bucketStart, long version, List<Occupant> occupants) {

    /** A confirmed reservation whose interval intersects this bucket. */
    public record Occupant(String reservationId, TimeInterval interval) {
    }

    public static SlotBucket empty(String key, String resourceId, Instant bucketStart) {
        return new SlotBucket(key, resourceId, bucketStart, 0, List.of());
    }

    public Optional<Occupant> clashWith(TimeInterval requested) {
        return occupants.stream().filter(o -> o.interval().overlaps(requested)).findFirst();
    }

    public SlotBucket withOccupant(Occupant occupant) {
        List<Occupant> next = new ArrayList<>(occupants);
        next.add(occupant);
        return new SlotBucket(key, resourceId, bucketStart, version + 1, List.copyOf(next));
    }

    public SlotBucket withoutOccupant(String reservationId) {
        List<Occupant> next = occupants.stream().filter(o -> !o.reservationId().equals(reservationId)).toList();
        return new SlotBucket(key, resourceId, bucketStart, version + 1, next);
    }
}

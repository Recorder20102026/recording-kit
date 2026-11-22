package com.acme.reservations.testkit;

import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Resource;
import com.acme.reservations.domain.TimeInterval;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public final class Fixtures {

    public static final String HOT_COURT = "COURT-1";
    public static final String OTHER_COURT = "COURT-2";
    public static final String DAY = "2026-12-05";

    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static ReservationConfig config;

    private Fixtures() {
    }

    public static synchronized ReservationConfig config() {
        if (config == null) {
            config = ReservationConfig.load();
        }
        return config;
    }

    /** A fresh production-shaped deployment (config/reservation.properties, 3 nodes) with two courts. */
    public static Cluster cluster() {
        return cluster(Constraints.NODES);
    }

    public static Cluster cluster(int nodes) {
        return Cluster.start(config(), nodes)
                .withResource(Resource.active(HOT_COURT, "Centre Court", 4_000))
                .withResource(Resource.active(OTHER_COURT, "Court 2", 3_000));
    }

    /** {@code slot("09:30", "10:30")} on the fixture day. */
    public static TimeInterval slot(String from, String to) {
        return new TimeInterval(Instant.parse(DAY + "T" + from + ":00Z"), Instant.parse(DAY + "T" + to + ":00Z"));
    }

    public static CreateReservationRequest request(String resourceId, TimeInterval interval) {
        long n = REQUEST_IDS.incrementAndGet();
        return new CreateReservationRequest("idem-" + n, resourceId, "cust-" + n, interval);
    }

    public static CreateReservationRequest request(TimeInterval interval) {
        return request(HOT_COURT, interval);
    }
}

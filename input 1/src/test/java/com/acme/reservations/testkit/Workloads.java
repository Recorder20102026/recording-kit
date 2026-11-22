package com.acme.reservations.testkit;

import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.TimeInterval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static com.acme.reservations.testkit.Fixtures.HOT_COURT;
import static com.acme.reservations.testkit.Fixtures.slot;

/** Production-shaped request mixes. */
public final class Workloads {

    /**
     * Saturday-morning release on Centre Court. Hot slots partially overlap each other:
     * <pre>
     *  09:00 ─────── 10:00                      S1
     *        09:30 ─────── 10:30                S2
     *              10:00 ─────── 11:00          S3
     *                         10:45 ─ 11:15     S4
     *                             11:00 ─────── 12:00   S5
     *                                        13:00 ─── 14:00   S6 (isolated)
     * </pre>
     */
    public static final List<TimeInterval> HOT_SLOTS = List.of(
            slot("09:00", "10:00"),
            slot("09:30", "10:30"),
            slot("10:00", "11:00"),
            slot("10:45", "11:15"),
            slot("11:00", "12:00"),
            slot("13:00", "14:00"));

    /** Quiet evening slots on the SAME court - nobody competes for them. */
    public static final List<TimeInterval> COLD_SLOTS = List.of(
            slot("15:00", "15:30"), slot("15:30", "16:00"), slot("16:00", "16:30"), slot("16:30", "17:00"),
            slot("17:00", "17:30"), slot("17:30", "18:00"), slot("18:00", "18:30"), slot("18:30", "19:00"),
            slot("19:00", "19:30"), slot("19:30", "20:00"));

    private Workloads() {
    }

    public record Burst(List<CreateReservationRequest> hot, List<CreateReservationRequest> cold) {
        public List<CreateReservationRequest> all(long seed) {
            List<CreateReservationRequest> all = new ArrayList<>(hot);
            all.addAll(cold);
            Collections.shuffle(all, new Random(seed));
            return all;
        }

        public boolean isCold(CreateReservationRequest r) {
            return cold.contains(r);
        }
    }

    /** {@code burstFactor} identical requests for each hot slot + one request per cold slot. */
    public static Burst hotSlotBurst(int burstFactor) {
        List<CreateReservationRequest> hot = new ArrayList<>();
        for (TimeInterval s : HOT_SLOTS) {
            for (int i = 0; i < burstFactor; i++) {
                hot.add(Fixtures.request(HOT_COURT, s));
            }
        }
        List<CreateReservationRequest> cold = COLD_SLOTS.stream().map(s -> Fixtures.request(HOT_COURT, s)).toList();
        return new Burst(hot, cold);
    }
}

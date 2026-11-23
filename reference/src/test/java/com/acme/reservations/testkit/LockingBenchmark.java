package com.acme.reservations.testkit;

import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.domain.CreateReservationRequest;

import java.util.*;

/** Race / 20x burst harness for candidate locking strategies: ./scripts/benchmark.sh pessimistic fenced */
public final class LockingBenchmark {

    static final int RACE_ROUNDS = 10;
    static final int BURST_ROUNDS = 3;

    private LockingBenchmark() {
    }

    public static void main(String[] args) {
        List<String> strategies = args.length == 0 ? List.of("configured") : List.of(args);
        List<String> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String strategy : strategies) {
            if (!strategy.equals("configured")) {
                System.setProperty("reservation.locking.strategy", strategy);
            }
            ReservationConfig config = ReservationConfig.load();
            System.out.println();
            System.out.println("▶ strategy: " + strategy);
            Verdict verdict = new Verdict();
            overlapRace(config, verdict);
            burst(config, verdict);
            try {
                verdict.assertAll();
                accepted.add(strategy);
                System.out.println("   verdict: ACCEPT");
            } catch (AssertionError e) {
                rejected.add(strategy);
                System.out.println("   verdict: REJECT, " + e.getMessage().split(":")[0]);
            }
        }
        System.out.println();
        System.out.println("RESULT: accepted=" + accepted + "  rejected=" + rejected);
    }

    // Written in the service itself: overlapRace (cross-pod overlap race), burst (20x burst), checkConstraints.
}

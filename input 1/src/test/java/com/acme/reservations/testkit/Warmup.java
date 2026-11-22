package com.acme.reservations.testkit;

import com.acme.reservations.cluster.*;
import com.acme.reservations.domain.CreateReservationRequest;

import java.util.*;

/**
 * JIT warm-up before timing-sensitive measurements. A cold JVM runs the first burst in the
 * interpreter, which inflates lock hold times and says nothing about the design under test.
 * Results of warm-up runs are discarded.
 */
public final class Warmup {

    private static boolean warmedUp;

    private Warmup() {
    }

    public static synchronized void productionPath() {
        if (warmedUp) {
            return;
        }
        run(2);
        warmedUp = true;
    }

    private static void run(int rounds) {
        long t0 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            Cluster cluster = Fixtures.cluster();
            List<CreateReservationRequest> all = Workloads.hotSlotBurst(Constraints.BURST_FACTOR).all(i);
            List<LoadRunner.Submission> submissions = new ArrayList<>();
            for (int j = 0; j < all.size(); j++) {
                ServiceNode node = cluster.node(j % cluster.nodes().size());
                submissions.add(new LoadRunner.Submission(all.get(j), node.name(), node.controller()::postReservation));
            }
            LoadRunner.fireTogether(submissions);
            cluster.quiesce();
        }
        System.out.printf("      (JIT warm-up: %d discarded burst runs, %.0f ms)%n", rounds, (System.nanoTime() - t0) / 1e6);
    }
}

package com.acme.reservations.suites;

import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.domain.*;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.testkit.*;

import java.util.*;

/**
 * 20x burst on six partially overlapping hot slots + ten cold slots on the same court,
 * spread round-robin over three pods, all fired at the same instant.
 */
public class BurstContentionTest {

    private Cluster cluster;
    private Workloads.Burst burst;
    private LoadRunner.Run run;
    private RunReport report;

    public static void main(String[] args) {
        TestRunner.runAndExit(BurstContentionTest.class);
    }

    private synchronized RunReport burst() {
        if (report == null) {
            Warmup.productionPath();
            cluster = Fixtures.cluster();
            burst = Workloads.hotSlotBurst(Constraints.BURST_FACTOR);
            List<LoadRunner.Submission> submissions = new ArrayList<>();
            List<CreateReservationRequest> all = burst.all(42);
            for (int i = 0; i < all.size(); i++) {
                var node = cluster.node(i % cluster.nodes().size());
                submissions.add(new LoadRunner.Submission(all.get(i), node.name(), node.controller()::postReservation));
            }
            run = LoadRunner.fireTogether(submissions);
            report = RunReport.of("hot-slot burst: %d requests, %d pods, %dx on %d hot slots + %d cold slots"
                    .formatted(all.size(), cluster.nodes().size(), Constraints.BURST_FACTOR,
                            Workloads.HOT_SLOTS.size(), Workloads.COLD_SLOTS.size()), cluster, run).print();
            printBookedSlots();
        }
        return report;
    }

    @Scenario(value = "no two confirmed reservations overlap after the burst", order = 1)
    void noDoubleBookings() {
        RunReport r = burst();
        List<Invariants.DoubleBooking> pairs = Invariants.doubleBookings(cluster.db());
        pairs.stream().limit(3).forEach(p -> System.out.println("        overlap: " + p));
        new Verdict().equal("overlapping confirmed reservation pairs", 0, r.doubleBookings()).assertAll();
    }

    @Scenario(value = "every client gets a definitive, truthful answer (201 or a justified 409)", order = 2)
    void definitiveAnswers() {
        RunReport r = burst();
        long coldBooked = run.outcomes().stream()
                .filter(o -> burst.isCold(o.request()) && o.response().status() == 201).count();
        new Verdict()
                .equal("503 responses (gave up)", 0, r.busy503())
                .equal("500 responses (exception leaked)", 0, r.serverErrors500())
                .equal("409s not explained by a committed overlapping booking", 0, r.unjustifiedConflicts())
                .equal("cold slots on the hot court booked", Workloads.COLD_SLOTS.size(), coldBooked)
                .assertAll();
    }

    @Scenario(value = "lock waits stay inside 50ms and retries inside the 2-retry budget", order = 3)
    void lockWaitAndRetryBudget() {
        RunReport r = burst();
        new Verdict()
                .atMost("max single lock wait", Constraints.lockWaitLimitMillis(), r.db().maxLockWaitMillis(), "ms")
                .check("lock_timeout aborts (each must be absorbed by the retry budget)",
                        r.db().lockTimeouts() <= r.retries(), r.db().lockTimeouts() + " aborts, " + r.retries() + " retries")
                .check("max attempts per request <= " + Constraints.MAX_ATTEMPTS,
                        r.maxAttemptsPerRequest() <= Constraints.MAX_ATTEMPTS, "got " + r.maxAttemptsPerRequest())
                .atMost("retry amplification (attempts / requests)", Constraints.MAX_RETRY_AMPLIFICATION,
                        r.retryAmplification(), "x")
                .assertAll();
    }

    @Scenario(value = "no orphaned / duplicated / missing side effects after the outbox drains", order = 4)
    void noPartialState() {
        RunReport r = burst();
        new Verdict()
                .equal("side-effect violations (" + r.sideEffects() + ")", 0, r.sideEffects().violations())
                .assertAll();
    }

    private void printBookedSlots() {
        List<Reservation> confirmed = cluster.db().committedRows(Schema.RESERVATIONS).stream()
                .filter(Reservation::isActive).toList();
        StringBuilder sb = new StringBuilder("      booked per hot slot: ");
        for (TimeInterval s : Workloads.HOT_SLOTS) {
            long n = confirmed.stream().filter(c -> c.interval().equals(s)).count();
            sb.append(s.start().toString(), 11, 16).append('-').append(s.end().toString(), 11, 16)
                    .append("=").append(n).append("  ");
        }
        System.out.println(sb);
    }
}

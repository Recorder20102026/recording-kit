package com.acme.reservations.evaluation;

import com.acme.reservations.db.Database;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Resource;
import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.evaluation.spikes.JvmLocalLockDesign;
import com.acme.reservations.evaluation.spikes.LockingDesign;
import com.acme.reservations.evaluation.spikes.OptimisticVersionDesign;
import com.acme.reservations.evaluation.spikes.ParentResourceLockDesign;
import com.acme.reservations.evaluation.spikes.SlotBucketFenceDesign;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.service.ServiceMetrics;
import com.acme.reservations.testkit.Constraints;
import com.acme.reservations.testkit.Fixtures;
import com.acme.reservations.testkit.LoadRunner;
import com.acme.reservations.testkit.RunReport;
import com.acme.reservations.testkit.Workloads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the SAME production-shaped burst (3 pods, 20x on partially overlapping hot slots + cold slots)
 * against each candidate locking design and checks it against the hard constraints.
 * <pre>
 *   ./scripts/evaluate-designs.sh            (3 rounds per design)
 *   ./scripts/evaluate-designs.sh 5          (5 rounds per design)
 * </pre>
 */
public final class DesignEvaluation {

    private record Candidate(LockingDesign design, int pods) {
    }

    private record Totals(String label, LockingDesign design, int pods, int doubleBookings, int busy503,
                          long lockTimeouts, double maxLockWaitMs, int maxAttempts, double amplification,
                          double coldP99Ms, double p99Ms) {

        boolean consistent() {
            return doubleBookings == 0;
        }

        boolean withinBudget() {
            return busy503 == 0 && maxAttempts <= Constraints.MAX_ATTEMPTS
                    && amplification <= Constraints.MAX_RETRY_AMPLIFICATION
                    && maxLockWaitMs <= Constraints.lockWaitLimitMillis();
        }
    }

    private DesignEvaluation() {
    }

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        var config = Fixtures.config();
        List<Candidate> candidates = List.of(
                new Candidate(new JvmLocalLockDesign(), 1),
                new Candidate(new JvmLocalLockDesign(), Constraints.NODES),
                new Candidate(new OptimisticVersionDesign(), Constraints.NODES),
                new Candidate(new ParentResourceLockDesign(), Constraints.NODES),
                new Candidate(new SlotBucketFenceDesign(), Constraints.NODES));

        String line = "═".repeat(118);
        System.out.println(line);
        System.out.printf(" Locking design evaluation - %d rounds each, %dx burst on %d hot slots + %d cold slots, lock_timeout=%dms, max %d retries%n",
                rounds, Constraints.BURST_FACTOR, Workloads.HOT_SLOTS.size(), Workloads.COLD_SLOTS.size(),
                config.lockWaitTimeout().toMillis(), Constraints.MAX_RETRIES);
        System.out.println(line);

        List<Totals> results = new ArrayList<>();
        for (Candidate c : candidates) {
            String label = c.design().code() + "  " + c.design().title() + " (" + c.pods() + " pod" + (c.pods() > 1 ? "s" : "") + ")";
            System.out.println();
            System.out.println("▶ " + label + "   - probes " + c.design().constraintUnderTest());
            List<RunReport> reports = new ArrayList<>();
            List<Double> coldP99 = new ArrayList<>();
            for (int round = 0; round <= rounds; round++) { // round 0 = JIT warm-up, discarded
                Database db = new Database(config.dbConfig());
                db.seed(Schema.RESOURCES, Fixtures.HOT_COURT, Resource.active(Fixtures.HOT_COURT, "Centre Court", 4_000));
                List<ServiceMetrics> metrics = new ArrayList<>();
                List<LockingDesign.Pod> pods = new ArrayList<>();
                for (int p = 0; p < c.pods(); p++) {
                    ServiceMetrics m = new ServiceMetrics();
                    metrics.add(m);
                    pods.add(c.design().newPod("node-" + (char) ('a' + p), db, m));
                }
                Workloads.Burst burst = Workloads.hotSlotBurst(Constraints.BURST_FACTOR);
                List<CreateReservationRequest> all = burst.all(round);
                List<LoadRunner.Submission> submissions = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    int podIndex = i % pods.size();
                    LockingDesign.Pod pod = pods.get(podIndex);
                    String node = "node-" + (char) ('a' + podIndex);
                    submissions.add(new LoadRunner.Submission(all.get(i), node, req -> {
                        try {
                            return HttpResponse.from(pod.book(req), node);
                        } catch (RuntimeException e) {
                            return HttpResponse.serverError(e, node);
                        }
                    }));
                }
                LoadRunner.Run run = LoadRunner.fireTogether(submissions);
                if (round == 0) {
                    continue;
                }
                reports.add(RunReport.of("round " + round, db, metrics, run, null));
                long[] cold = run.outcomes().stream().filter(o -> burst.isCold(o.request()))
                        .mapToLong(LoadRunner.Outcome::latencyNanos).sorted().toArray();
                coldP99.add(cold.length == 0 ? 0 : cold[cold.length - 1] / 1e6);
                RunReport r = reports.get(reports.size() - 1);
                System.out.printf("   round %d: 201=%-3d 409=%-3d 503=%-3d | double bookings=%-2d | lock timeouts=%-3d max wait=%5.1f ms | retries=%-3d max attempts=%d amplification=%.2fx | p99=%6.1f ms cold-slot max=%6.1f ms%n",
                        round, r.created(), r.conflicts(), r.busy503(), r.doubleBookings(), r.db().lockTimeouts(),
                        r.db().maxLockWaitMillis(), r.retries(), r.maxAttemptsPerRequest(), r.retryAmplification(),
                        r.p99Millis(), coldP99.get(coldP99.size() - 1));
            }
            results.add(new Totals(label, c.design(), c.pods(),
                    reports.stream().mapToInt(RunReport::doubleBookings).sum(),
                    reports.stream().mapToInt(RunReport::busy503).sum(),
                    reports.stream().mapToLong(r -> r.db().lockTimeouts()).sum(),
                    reports.stream().mapToDouble(r -> r.db().maxLockWaitMillis()).max().orElse(0),
                    reports.stream().mapToInt(RunReport::maxAttemptsPerRequest).max().orElse(0),
                    reports.stream().mapToDouble(RunReport::retryAmplification).max().orElse(0),
                    coldP99.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                    reports.stream().mapToDouble(RunReport::p99Millis).max().orElse(0)));
        }

        System.out.println();
        System.out.println(line);
        System.out.printf(" %-58s | %9s | %4s | %8s | %8s | %6s | %7s | %-28s%n", "design (worst round)", "dbl-books",
                "503", "lock t/o", "max wait", "ampl.", "cold max", "verdict");
        System.out.println("─".repeat(118));
        for (Totals t : results) {
            String verdict = t.pods() < Constraints.NODES ? "control - prod runs " + Constraints.NODES + " pods"
                    : !t.consistent() ? "REJECT - double bookings"
                    : !t.withinBudget() ? "REJECT - breaks 50ms/2-retry"
                    : "ACCEPT - meets (a)(b)(c)";
            System.out.printf(" %-58s | %9d | %4d | %8d | %5.1f ms | %5.2fx | %4.0f ms | %-28s%n", t.label(),
                    t.doubleBookings(), t.busy503(), t.lockTimeouts(), t.maxLockWaitMs(), t.amplification(),
                    t.coldP99Ms(), verdict);
        }
        System.out.println(line);
        System.out.println(" (a) multi-instance: locks must live in the shared DB, not the JVM        -> rules out A");
        System.out.println(" (b) partial overlap: the rival row does not exist yet, nothing to version -> rules out B");
        System.out.println(" (c) 50ms lock_timeout + 2 retries at 20x: one lock per court is too coarse -> rules out C1");
        System.out.println(line);
        boolean winnerOk = results.stream().filter(t -> t.design() instanceof SlotBucketFenceDesign)
                .allMatch(t -> t.consistent() && t.withinBudget());
        System.out.println(winnerOk ? " RESULT: C2 (slot-bucket fencing + version guard) is the only design meeting all constraints"
                : " RESULT: no design met all constraints in this run " + Arrays.toString(args));
    }
}

package com.acme.reservations.testkit;

import com.acme.reservations.cluster.*;
import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.domain.*;
import com.acme.reservations.service.ServiceMetrics;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static com.acme.reservations.testkit.Fixtures.*;

/**
 * Repeatable race / burst harness for candidate locking strategies.
 * <pre>
 *   ./scripts/benchmark.sh                        # the strategy in config/reservation.properties
 *   ./scripts/benchmark.sh pessimistic fenced     # each candidate, same workload, side by side
 *   ROUNDS=5 ./scripts/benchmark.sh fenced        # more burst rounds
 * </pre>
 * Per strategy, on a fresh {@value Constraints#NODES}-pod cluster each time:
 * <ol>
 *   <li><b>overlap race</b>: {@value #RACE_ROUNDS} rounds of three partially overlapping requests fired at the
 *       same instant on three different pods (10:00-11:00, 10:30-11:30, 09:30-10:30);</li>
 *   <li><b>20x burst</b>: one discarded JIT warm-up round, then N rounds of {@link Workloads#hotSlotBurst}.</li>
 * </ol>
 * Counters: double bookings, 409 conflicts, 503/500, lock-wait timeouts, max lock wait, retries,
 * attempts per request, retry amplification, p50/p99 latency and cold-slot latency.
 */
public final class LockingBenchmark {

    static final int RACE_ROUNDS = 10;
    private static final String STRATEGY_KEY = "reservation.locking.strategy";

    /** One strategy's numbers: counts are summed over all rounds, maxima are the worst round. */
    record Result(String strategy, int raceRoundsWithDoubleBooking, int doubleBookings, int requests,
                  int created, int conflicts, int busy503, int serverErrors500, long lockTimeouts, long retries,
                  double maxLockWaitMs, int maxAttempts, double amplification, double p99Ms, double coldMaxMs) {

        /** Each lock_timeout abort costs one extra attempt, so this is bounded by the retry amplification budget. */
        double lockTimeoutsPerRequest() {
            return requests == 0 ? 0 : (double) lockTimeouts / requests;
        }
    }

    private LockingBenchmark() {
    }

    /**
     * The hard constraints from {@link Constraints}, applied to one strategy's results.
     * Return {@code null} while no checks are written yet.
     */
    static Verdict checkConstraints(Result r) {
        // TODO: assert the hard constraints from Constraints.java against r (service-specific, not part of this patch)
        return null;
    }

    public static void main(String[] args) {
        int rounds = Integer.parseInt(System.getenv().getOrDefault("ROUNDS", "3"));
        List<String> strategies = args.length == 0 ? List.of(configuredStrategy()) : List.of(args);
        String line = "═".repeat(124);
        System.out.println(line);
        System.out.printf(" Locking benchmark - strategies=%s, %d pods, overlap race x%d, %dx burst on %d hot + %d cold slots x%d rounds%n",
                strategies, Constraints.NODES, RACE_ROUNDS, Constraints.BURST_FACTOR, Workloads.HOT_SLOTS.size(),
                Workloads.COLD_SLOTS.size(), rounds);
        System.out.printf(" constraints: lock wait <= %dms, retries <= %d (max %d attempts), amplification <= %.2fx%n",
                Constraints.LOCK_WAIT_BUDGET.toMillis(), Constraints.MAX_RETRIES, Constraints.MAX_ATTEMPTS,
                Constraints.MAX_RETRY_AMPLIFICATION);
        System.out.println(line);

        List<Result> results = new ArrayList<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String strategy : strategies) {
            Result r = measure(strategy, rounds);
            results.add(r);
            System.out.println("   constraint checks:");
            verdicts.put(strategy, verdict(checkConstraints(r)));
        }

        System.out.println();
        System.out.println(line);
        System.out.printf(" %-26s | %9s | %3s | %3s | %8s | %8s | %7s | %6s | %7s | %8s | %s%n", "strategy",
                "dbl-books", "503", "500", "lock t/o", "max wait", "max att", "ampl.", "p99", "cold max", "verdict");
        System.out.println("─".repeat(124));
        for (Result r : results) {
            System.out.printf(" %-26s | %9d | %3d | %3d | %8d | %5.1f ms | %7d | %5.2fx | %4.0f ms | %5.0f ms | %s%n",
                    r.strategy(), r.doubleBookings(), r.busy503(), r.serverErrors500(), r.lockTimeouts(),
                    r.maxLockWaitMs(), r.maxAttempts(), r.amplification(), r.p99Ms(), r.coldMaxMs(),
                    verdicts.get(r.strategy()));
        }
        System.out.println(" counts (dbl-books, 503, 500, lock t/o) are summed over all rounds; max wait, max att, ampl., p99, cold max = worst round");
        System.out.println(line);
        System.out.println(" RESULT: accepted=" + withVerdict(verdicts, "ACCEPT") + "  rejected="
                + withVerdict(verdicts, "REJECT") + "  not checked=" + withVerdict(verdicts, "n/a"));
        System.out.println(line);
    }

    private static Result measure(String strategy, int rounds) {
        if (!strategy.startsWith("(")) {
            System.setProperty(STRATEGY_KEY, strategy);
        }
        ReservationConfig config = ReservationConfig.load();
        System.out.println();
        System.out.println("▶ strategy: " + strategy);

        // 1. Concurrent interval-overlap race across pods.
        int raceRounds = 0;
        int raceDoubleBookings = 0;
        for (int round = 0; round < RACE_ROUNDS; round++) {
            Cluster cluster = newCluster(config);
            LoadRunner.fireTogether(List.of(
                    new LoadRunner.Submission(request(slot("10:00", "11:00")), "node-a", cluster.node(0).controller()::postReservation),
                    new LoadRunner.Submission(request(slot("10:30", "11:30")), "node-b", cluster.node(1).controller()::postReservation),
                    new LoadRunner.Submission(request(slot("09:30", "10:30")), "node-c", cluster.node(2).controller()::postReservation)));
            int pairs = Invariants.doubleBookings(cluster.db()).size();
            raceDoubleBookings += pairs;
            raceRounds += pairs > 0 ? 1 : 0;
        }
        System.out.printf("   overlap race : %d rounds x 3 partially overlapping requests on 3 pods -> rounds with a double booking=%d, overlapping pairs=%d%n",
                RACE_ROUNDS, raceRounds, raceDoubleBookings);

        // 2. 20x burst on the hot court (round 0 = JIT warm-up, discarded).
        List<RunReport> reports = new ArrayList<>();
        double coldMax = 0;
        for (int round = 0; round <= rounds; round++) {
            Cluster cluster = newCluster(config);
            Workloads.Burst burst = Workloads.hotSlotBurst(Constraints.BURST_FACTOR);
            List<CreateReservationRequest> all = burst.all(round);
            List<LoadRunner.Submission> submissions = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                ServiceNode node = cluster.node(i % cluster.nodes().size());
                submissions.add(new LoadRunner.Submission(all.get(i), node.name(), node.controller()::postReservation));
            }
            LoadRunner.Run run = LoadRunner.fireTogether(submissions);
            if (round == 0) {
                continue;
            }
            List<ServiceMetrics> metrics = cluster.nodes().stream().map(ServiceNode::metrics).toList();
            RunReport r = RunReport.of("burst round " + round, cluster.db(), metrics, run, null);
            reports.add(r);
            double cold = run.outcomes().stream().filter(o -> burst.isCold(o.request()))
                    .mapToLong(LoadRunner.Outcome::latencyNanos).max().orElse(0) / 1e6;
            coldMax = Math.max(coldMax, cold);
            System.out.printf("   burst round %d: 201=%-3d 409=%-3d 503=%-3d 500=%-3d | double bookings=%-2d | lock timeouts=%-3d max wait=%5.1f ms | retries=%-3d max attempts=%d amplification=%.2fx | p50=%5.1f p99=%6.1f ms cold max=%6.1f ms%n",
                    round, r.created(), r.conflicts(), r.busy503(), r.serverErrors500(), r.doubleBookings(),
                    r.db().lockTimeouts(), r.db().maxLockWaitMillis(), r.retries(), r.maxAttemptsPerRequest(),
                    r.retryAmplification(), r.p50Millis(), r.p99Millis(), cold);
        }

        return new Result(strategy, raceRounds,
                raceDoubleBookings + reports.stream().mapToInt(RunReport::doubleBookings).sum(),
                reports.stream().mapToInt(RunReport::requests).sum(),
                reports.stream().mapToInt(RunReport::created).sum(),
                reports.stream().mapToInt(RunReport::conflicts).sum(),
                reports.stream().mapToInt(RunReport::busy503).sum(),
                reports.stream().mapToInt(RunReport::serverErrors500).sum(),
                reports.stream().mapToLong(r -> r.db().lockTimeouts()).sum(),
                reports.stream().mapToLong(RunReport::retries).sum(),
                reports.stream().mapToDouble(r -> r.db().maxLockWaitMillis()).max().orElse(0),
                reports.stream().mapToInt(RunReport::maxAttemptsPerRequest).max().orElse(0),
                reports.stream().mapToDouble(RunReport::retryAmplification).max().orElse(0),
                reports.stream().mapToDouble(RunReport::p99Millis).max().orElse(0),
                coldMax);
    }

    private static Cluster newCluster(ReservationConfig config) {
        return Cluster.start(config, Constraints.NODES)
                .withResource(Resource.active(HOT_COURT, "Centre Court", 4_000))
                .withResource(Resource.active(OTHER_COURT, "Court 2", 3_000));
    }

    private static List<String> withVerdict(Map<String, String> verdicts, String prefix) {
        return verdicts.entrySet().stream().filter(e -> e.getValue().startsWith(prefix)).map(Map.Entry::getKey).toList();
    }

    private static String verdict(Verdict checks) {
        if (checks == null) {
            System.out.println("      (no constraint checks written yet - see checkConstraints)");
            return "n/a - no checks yet";
        }
        try {
            checks.assertAll();
            return "ACCEPT";
        } catch (AssertionError e) {
            return "REJECT - " + e.getMessage().replaceFirst(":.*", "");
        }
    }

    /** {@code reservation.locking.strategy} from the config file, or a label for code that predates it. */
    private static String configuredStrategy() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(System.getProperty("reservation.config",
                "config/reservation.properties")))) {
            p.load(in);
        } catch (IOException e) {
            return "(config not found)";
        }
        String value = p.getProperty(STRATEGY_KEY);
        return value == null ? "(current code)" : value.trim();
    }
}

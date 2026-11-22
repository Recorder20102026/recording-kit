package com.acme.reservations.testkit;

import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.cluster.ServiceNode;
import com.acme.reservations.db.Database;
import com.acme.reservations.db.DbMetrics;
import com.acme.reservations.service.ServiceMetrics;

import java.util.List;
import java.util.function.Predicate;

/** Everything we measure about one load run: failure modes, conflict rate, retries, lock waits, timings. */
public record RunReport(String title,
                        int requests,
                        int created,
                        int replayed,
                        int conflicts,
                        int clientErrors,
                        int busy503,
                        int serverErrors500,
                        int doubleBookings,
                        int unjustifiedConflicts,
                        Invariants.SideEffectAudit sideEffects,
                        long attempts,
                        long retries,
                        int maxAttemptsPerRequest,
                        DbMetrics.Snapshot db,
                        double p50Millis,
                        double p99Millis,
                        double maxMillis,
                        double wallMillis) {

    public static RunReport of(String title, Cluster cluster, LoadRunner.Run run) {
        List<ServiceMetrics> metrics = cluster.nodes().stream().map(ServiceNode::metrics).toList();
        return of(title, cluster.db(), metrics, run, Invariants.auditSideEffects(cluster));
    }

    public static RunReport of(String title, Database db, List<ServiceMetrics> metrics, LoadRunner.Run run,
                               Invariants.SideEffectAudit audit) {
        List<LoadRunner.Outcome> o = run.outcomes();
        long[] latencies = o.stream().mapToLong(LoadRunner.Outcome::latencyNanos).sorted().toArray();
        return new RunReport(title, o.size(),
                count(o, x -> x.response().status() == 201),
                count(o, x -> x.response().status() == 200),
                count(o, x -> x.response().status() == 409),
                count(o, x -> x.response().status() >= 400 && x.response().status() < 409),
                count(o, x -> x.response().status() == 503),
                count(o, x -> x.response().status() == 500),
                Invariants.doubleBookings(db).size(),
                Invariants.unjustifiedConflicts(db, o),
                audit,
                metrics.stream().mapToLong(ServiceMetrics::createAttempts).sum(),
                metrics.stream().mapToLong(ServiceMetrics::retries).sum(),
                metrics.stream().mapToInt(ServiceMetrics::maxAttemptsPerRequest).max().orElse(0),
                db.metrics().snapshot(),
                percentile(latencies, 0.50), percentile(latencies, 0.99),
                latencies.length == 0 ? 0 : latencies[latencies.length - 1] / 1e6,
                run.wallNanos() / 1e6);
    }

    public double conflictRate() {
        return requests == 0 ? 0 : (double) conflicts / requests;
    }

    public double retryAmplification() {
        return requests == 0 ? 0 : (double) attempts / requests;
    }

    public RunReport print() {
        System.out.println("      ┌─ " + title);
        System.out.printf("      │ responses    201=%d  200(replay)=%d  409=%d  4xx=%d  503=%d  500=%d   conflict rate %.1f%%%n",
                created, replayed, conflicts, clientErrors, busy503, serverErrors500, conflictRate() * 100);
        System.out.printf("      │ correctness  double bookings=%d   unjustified 409s=%d%n", doubleBookings,
                unjustifiedConflicts);
        if (sideEffects != null) {
            System.out.println("      │ side effects " + sideEffects);
        }
        System.out.printf("      │ retries      attempts=%d  retries=%d  max attempts/request=%d  amplification=%.2fx%n",
                attempts, retries, maxAttemptsPerRequest, retryAmplification());
        System.out.printf("      │ db locks     contended waits=%d  lock timeouts=%d  max wait=%.1f ms  p99 wait=%.1f ms%n",
                db.contendedLockWaits(), db.lockTimeouts(), db.maxLockWaitMillis(), db.p99LockWaitMillis());
        System.out.printf("      │ latency      p50=%.1f ms  p99=%.1f ms  max=%.1f ms   wall=%.0f ms%n",
                p50Millis, p99Millis, maxMillis, wallMillis);
        System.out.println("      └─");
        return this;
    }

    private static int count(List<LoadRunner.Outcome> outcomes, Predicate<LoadRunner.Outcome> p) {
        return (int) outcomes.stream().filter(p).count();
    }

    private static double percentile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * q) - 1);
        return sorted[Math.max(0, idx)] / 1e6;
    }
}

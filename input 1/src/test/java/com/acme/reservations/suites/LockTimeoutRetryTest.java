package com.acme.reservations.suites;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.db.DbMetrics;
import com.acme.reservations.testkit.Constraints;
import com.acme.reservations.testkit.Fixtures;
import com.acme.reservations.testkit.Invariants;
import com.acme.reservations.testkit.Scenario;
import com.acme.reservations.testkit.TestRunner;
import com.acme.reservations.testkit.Verdict;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static com.acme.reservations.testkit.Fixtures.request;
import static com.acme.reservations.testkit.Fixtures.slot;

/** Behaviour at the edge of the 50ms lock_timeout and the 2-retry budget. */
public class LockTimeoutRetryTest {

    /** Upper bound for a request that exhausts its budget: 3 x 50ms waits + backoff + slack. */
    private static final double MAX_GIVE_UP_LATENCY_MS = 250;

    public static void main(String[] args) {
        TestRunner.runAndExit(LockTimeoutRetryTest.class);
    }

    @Scenario(value = "lock holder stalls 600ms -> waiter times out at 50ms, retries <= 2, answers 503", order = 1)
    void stalledHolderBoundedWaiter() throws Exception {
        Cluster cluster = Fixtures.cluster();
        cluster.db().faults().stallNextCommits("reservation.create", 1, Duration.ofMillis(600));

        CompletableFuture<HttpResponse> holder = CompletableFuture.supplyAsync(
                () -> cluster.node(0).controller().postReservation(request(slot("10:00", "11:00"))));
        Thread.sleep(40); // holder is now parked before COMMIT, holding its locks

        long t0 = System.nanoTime();
        HttpResponse waiter = cluster.node(1).controller().postReservation(request(slot("10:30", "11:30")));
        double waiterMillis = (System.nanoTime() - t0) / 1e6;
        HttpResponse holderResponse = holder.get();

        DbMetrics.Snapshot db = cluster.db().metrics().snapshot();
        int waiterAttempts = cluster.node(1).metrics().maxAttemptsPerRequest();
        Invariants.SideEffectAudit audit = Invariants.auditSideEffects(cluster);
        System.out.printf("        waiter: HTTP %d %s  attempts=%d  latency=%.0f ms  lock timeouts=%d  max wait=%.1f ms%n",
                waiter.status(), waiter.body(), waiterAttempts, waiterMillis, db.lockTimeouts(), db.maxLockWaitMillis());

        new Verdict()
                .equal("stalled holder eventually books", 201, holderResponse.status())
                .equal("waiter answered 503 + Retry-After (not 500, not a double booking)", 503, waiter.status())
                .check("waiter attempts <= " + Constraints.MAX_ATTEMPTS, waiterAttempts <= Constraints.MAX_ATTEMPTS,
                        "got " + waiterAttempts)
                .atMost("each lock wait", Constraints.lockWaitLimitMillis(), db.maxLockWaitMillis(), "ms")
                .atMost("waiter gives up within", MAX_GIVE_UP_LATENCY_MS, waiterMillis, "ms")
                .equal("double bookings", 0, Invariants.doubleBookings(cluster.db()).size())
                .equal("side-effect violations (" + audit + ")", 0, audit.violations())
                .assertAll();
    }

    @Scenario(value = "persistent COMMIT failures -> stop after 2 retries, no money left on hold", order = 2)
    void persistentFailuresRespectRetryBudget() {
        Cluster cluster = Fixtures.cluster();
        cluster.db().faults().failNextCommits("reservation.create", 10);

        HttpResponse response = cluster.node(0).controller().postReservation(request(slot("12:00", "13:00")));
        cluster.db().faults().reset();
        int attempts = cluster.node(0).metrics().maxAttemptsPerRequest();
        Invariants.SideEffectAudit audit = Invariants.auditSideEffects(cluster);
        System.out.printf("        HTTP %d %s  attempts=%d  payment calls=%d%n", response.status(), response.body(),
                attempts, cluster.paymentGateway().calls());

        new Verdict()
                .check("attempts <= " + Constraints.MAX_ATTEMPTS, attempts <= Constraints.MAX_ATTEMPTS, "got " + attempts)
                .equal("client gets 503 + Retry-After", 503, response.status())
                .equal("active card holds left behind", 0, audit.orphanHolds())
                .equal("side-effect violations (" + audit + ")", 0, audit.violations())
                .assertAll();
    }

    @Scenario(value = "a business conflict (409) is never retried", order = 3)
    void conflictIsNotRetried() {
        Cluster cluster = Fixtures.cluster();
        cluster.node(0).controller().postReservation(request(slot("10:00", "11:00")));
        HttpResponse response = cluster.node(1).controller().postReservation(request(slot("10:15", "10:45")));
        new Verdict()
                .equal("HTTP", 409, response.status())
                .equal("attempts for the conflicting request", 1, cluster.node(1).metrics().maxAttemptsPerRequest())
                .equal("retries", 0, cluster.node(1).metrics().retries())
                .assertAll();
    }
}

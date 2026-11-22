package com.acme.reservations.suites;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.cluster.ServiceNode;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.domain.ReservationResult;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.testkit.Fixtures;
import com.acme.reservations.testkit.Invariants;
import com.acme.reservations.testkit.LoadRunner;
import com.acme.reservations.testkit.Scenario;
import com.acme.reservations.testkit.TestRunner;
import com.acme.reservations.testkit.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.acme.reservations.testkit.Fixtures.request;
import static com.acme.reservations.testkit.Fixtures.slot;

/** Correctness must hold for the cluster, not for one JVM. */
public class MultiNodeConsistencyTest {

    public static void main(String[] args) {
        TestRunner.runAndExit(MultiNodeConsistencyTest.class);
    }

    @Scenario(value = "control: 21 identical requests on ONE pod -> exactly one booking", order = 1)
    void singleNodeControl() {
        int[] winners = sameSlotRace(1, 3);
        Verdict v = new Verdict();
        for (int i = 0; i < winners.length; i++) {
            v.equal("round " + (i + 1) + ": confirmed reservations for 14:00-15:00", 1, winners[i]);
        }
        v.assertAll();
    }

    @Scenario(value = "21 identical requests spread over THREE pods -> exactly one booking", order = 2)
    void sameSlotAcrossThreeNodes() {
        int[] winners = sameSlotRace(3, 3);
        Verdict v = new Verdict();
        for (int i = 0; i < winners.length; i++) {
            v.equal("round " + (i + 1) + ": confirmed reservations for 14:00-15:00", 1, winners[i]);
        }
        v.assertAll();
    }

    @Scenario(value = "client retry of the SAME request lands on another pod -> one reservation, same id", order = 3)
    void idempotentRetryAcrossNodes() {
        Verdict v = new Verdict();
        for (int round = 1; round <= 3; round++) {
            Cluster cluster = Fixtures.cluster();
            CreateReservationRequest req = request(slot("16:00", "17:00"));
            LoadRunner.Run run = LoadRunner.fireTogether(List.of(
                    new LoadRunner.Submission(req, "node-a", cluster.node(0).controller()::postReservation),
                    new LoadRunner.Submission(req, "node-b", cluster.node(1).controller()::postReservation)));
            List<Reservation> forRequest = cluster.db().committedRows(Schema.RESERVATIONS).stream()
                    .filter(r -> r.requestId().equals(req.requestId())).toList();
            Set<String> idsReturned = run.outcomes().stream()
                    .map(LoadRunner.Outcome::response)
                    .filter(HttpResponse::isSuccess)
                    .map(r -> ((ReservationResult.Booked) r.result()).reservation().id())
                    .collect(Collectors.toSet());
            String statuses = run.outcomes().stream().map(o -> String.valueOf(o.response().status()))
                    .collect(Collectors.joining("/"));
            v.equal("round " + round + ": reservations stored for one Idempotency-Key", 1, forRequest.size());
            v.check("round " + round + ": both calls succeed with the same reservation id",
                    run.outcomes().stream().allMatch(o -> o.response().isSuccess()) && idsReturned.size() == 1,
                    "HTTP " + statuses + ", distinct ids=" + idsReturned.size());
        }
        v.assertAll();
    }

    /** @return confirmed reservations per round */
    private int[] sameSlotRace(int nodes, int rounds) {
        int[] winners = new int[rounds];
        for (int round = 0; round < rounds; round++) {
            Cluster cluster = Fixtures.cluster(nodes);
            List<LoadRunner.Submission> submissions = new ArrayList<>();
            for (int i = 0; i < 21; i++) {
                ServiceNode node = cluster.node(i % nodes);
                submissions.add(new LoadRunner.Submission(request(slot("14:00", "15:00")), node.name(),
                        node.controller()::postReservation));
            }
            LoadRunner.Run run = LoadRunner.fireTogether(submissions);
            winners[round] = (int) cluster.db().committedRows(Schema.RESERVATIONS).stream()
                    .filter(Reservation::isActive).count();
            System.out.printf("        round %d: %d pod(s)  winners by pod=%s  double bookings=%d  wall=%.0f ms%n",
                    round + 1, nodes,
                    cluster.db().committedRows(Schema.RESERVATIONS).stream()
                            .collect(Collectors.groupingBy(Reservation::createdByNode, Collectors.counting())),
                    Invariants.doubleBookings(cluster.db()).size(), run.wallNanos() / 1e6);
        }
        return winners;
    }
}

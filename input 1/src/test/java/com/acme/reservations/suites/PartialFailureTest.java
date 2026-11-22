package com.acme.reservations.suites;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.domain.ReservationResult;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.testkit.Fixtures;
import com.acme.reservations.testkit.Invariants;
import com.acme.reservations.testkit.Scenario;
import com.acme.reservations.testkit.TestRunner;
import com.acme.reservations.testkit.Verdict;

import java.util.List;

import static com.acme.reservations.testkit.Fixtures.request;
import static com.acme.reservations.testkit.Fixtures.slot;

/** What happens to money and messages when the DB transaction does not end the way we hoped. */
public class PartialFailureTest {

    public static void main(String[] args) {
        TestRunner.runAndExit(PartialFailureTest.class);
    }

    @Scenario(value = "COMMIT fails once (connection reset) -> retry books it, no orphaned hold/event", order = 1)
    void commitFailureLeavesNoOrphans() {
        Cluster cluster = Fixtures.cluster();
        cluster.db().faults().failNextCommits("reservation.create", 1);
        CreateReservationRequest req = request(slot("10:00", "11:00"));

        HttpResponse response = cluster.node(0).controller().postReservation(req);
        Invariants.SideEffectAudit audit = Invariants.auditSideEffects(cluster);

        new Verdict()
                .equal("client response", 201, response.status())
                .equal("reservations stored", 1, cluster.db().committedRows(Schema.RESERVATIONS).size())
                .equal("card holds placed at the payment provider", 1, cluster.paymentGateway().holds().size())
                .equal("orphaned holds + orphaned events (" + audit + ")", 0, audit.orphans())
                .equal("all side-effect violations", 0, audit.violations())
                .assertAll();
    }

    @Scenario(value = "COMMIT succeeds but the ack is lost -> retry returns the SAME booking, not a 409", order = 2)
    void commitAckLostIsIdempotent() {
        Cluster cluster = Fixtures.cluster();
        cluster.db().faults().loseNextCommitAcks("reservation.create", 1);
        CreateReservationRequest req = request(slot("10:00", "11:00"));

        HttpResponse response = cluster.node(0).controller().postReservation(req);
        List<Reservation> stored = cluster.db().committedRows(Schema.RESERVATIONS);
        String returnedId = response.result() instanceof ReservationResult.Booked b ? b.reservation().id() : "-";
        Invariants.SideEffectAudit audit = Invariants.auditSideEffects(cluster);

        new Verdict()
                .check("client is told the booking succeeded (200/201)", response.isSuccess(),
                        "HTTP " + response.status() + " " + response.body())
                .equal("reservations stored", 1, stored.size())
                .check("returned id is the stored reservation", stored.size() == 1 && stored.get(0).id().equals(returnedId),
                        "returned " + returnedId)
                .equal("all side-effect violations (" + audit + ")", 0, audit.violations())
                .assertAll();
    }
}

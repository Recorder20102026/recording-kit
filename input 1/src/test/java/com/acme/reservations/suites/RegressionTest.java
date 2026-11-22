package com.acme.reservations.suites;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.domain.ReservationResult;
import com.acme.reservations.domain.ReservationStatus;
import com.acme.reservations.domain.TimeInterval;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.sideeffects.PaymentHold;
import com.acme.reservations.sideeffects.ReservationEvent;
import com.acme.reservations.testkit.Fixtures;
import com.acme.reservations.testkit.Invariants;
import com.acme.reservations.testkit.Scenario;
import com.acme.reservations.testkit.TestRunner;
import com.acme.reservations.testkit.Verdict;

import java.time.Instant;

import static com.acme.reservations.testkit.Fixtures.HOT_COURT;
import static com.acme.reservations.testkit.Fixtures.OTHER_COURT;
import static com.acme.reservations.testkit.Fixtures.request;
import static com.acme.reservations.testkit.Fixtures.slot;

/** Existing behaviour that must keep working after any change to the write path. */
public class RegressionTest {

    public static void main(String[] args) {
        TestRunner.runAndExit(RegressionTest.class);
    }

    @Scenario(value = "book a free slot -> 201, row stored, card hold + created event delivered", order = 1)
    void bookFreeSlot() {
        Cluster cluster = Fixtures.cluster();
        CreateReservationRequest req = request(slot("08:00", "09:00"));
        HttpResponse response = cluster.node(0).controller().postReservation(req);
        Invariants.SideEffectAudit audit = Invariants.auditSideEffects(cluster);
        Reservation stored = response.result() instanceof ReservationResult.Booked b
                ? cluster.db().committed(Schema.RESERVATIONS, b.reservation().id()).orElse(null) : null;
        new Verdict()
                .equal("HTTP", 201, response.status())
                .check("row stored as CONFIRMED with price 40.00", stored != null
                        && stored.status() == ReservationStatus.CONFIRMED && stored.amountCents() == 4_000, String.valueOf(stored))
                .equal("active holds", 1, cluster.paymentGateway().holds().size())
                .equal("created events", 1, cluster.eventBus().published().size())
                .equal("side-effect violations (" + audit + ")", 0, audit.violations())
                .assertAll();
    }

    @Scenario(value = "end before start -> 400 INVALID_INTERVAL", order = 2)
    void invalidInterval() {
        Cluster cluster = Fixtures.cluster();
        HttpResponse response = cluster.node(0).controller().postReservation(request(
                new TimeInterval(Instant.parse("2026-12-05T11:00:00Z"), Instant.parse("2026-12-05T10:00:00Z"))));
        new Verdict().equal("HTTP", 400, response.status()).assertAll();
    }

    @Scenario(value = "longer than 8h -> 400 BOOKING_TOO_LONG", order = 3)
    void tooLong() {
        Cluster cluster = Fixtures.cluster();
        HttpResponse response = cluster.node(0).controller().postReservation(request(slot("06:00", "15:00")));
        new Verdict().equal("HTTP", 400, response.status()).assertAll();
    }

    @Scenario(value = "unknown resource -> 404", order = 4)
    void unknownResource() {
        Cluster cluster = Fixtures.cluster();
        HttpResponse response = cluster.node(0).controller().postReservation(request("COURT-404", slot("10:00", "11:00")));
        new Verdict().equal("HTTP", 404, response.status()).assertAll();
    }

    @Scenario(value = "cancel -> 204, hold released, cancelled event, slot bookable again", order = 5)
    void cancelReleasesSlot() {
        Cluster cluster = Fixtures.cluster();
        HttpResponse booked = cluster.node(0).controller().postReservation(request(slot("10:00", "11:00")));
        String id = ((ReservationResult.Booked) booked.result()).reservation().id();
        cluster.quiesce();
        int cancel = cluster.node(1).controller().deleteReservation(id);
        cluster.quiesce();
        HttpResponse rebooked = cluster.node(2).controller().postReservation(request(slot("10:00", "11:00")));
        Invariants.SideEffectAudit audit = Invariants.auditSideEffects(cluster);
        new Verdict()
                .equal("DELETE", 204, cancel)
                .check("first hold released", cluster.paymentGateway().holds().stream()
                        .anyMatch(h -> h.reservationRef().equals(id) && h.status() == PaymentHold.Status.RELEASED), "")
                .check("cancelled event published", cluster.eventBus().published().stream()
                        .anyMatch(e -> e.type() == ReservationEvent.Type.RESERVATION_CANCELLED), "")
                .equal("re-booking the freed slot", 201, rebooked.status())
                .equal("side-effect violations (" + audit + ")", 0, audit.violations())
                .assertAll();
    }

    @Scenario(value = "cancel twice -> second DELETE is 409", order = 6)
    void cancelTwice() {
        Cluster cluster = Fixtures.cluster();
        HttpResponse booked = cluster.node(0).controller().postReservation(request(slot("10:00", "11:00")));
        String id = ((ReservationResult.Booked) booked.result()).reservation().id();
        new Verdict()
                .equal("first DELETE", 204, cluster.node(0).controller().deleteReservation(id))
                .equal("second DELETE", 409, cluster.node(1).controller().deleteReservation(id))
                .equal("DELETE unknown id", 404, cluster.node(1).controller().deleteReservation("R-does-not-exist"))
                .assertAll();
    }

    @Scenario(value = "back-to-back 20-minute bookings 10:00-10:20 and 10:20-10:40 both succeed", order = 7)
    void backToBackShortBookings() {
        Cluster cluster = Fixtures.cluster();
        new Verdict()
                .equal("10:00-10:20", 201, cluster.node(0).controller().postReservation(request(slot("10:00", "10:20"))).status())
                .equal("10:20-10:40", 201, cluster.node(1).controller().postReservation(request(slot("10:20", "10:40"))).status())
                .equal("10:40-11:00", 201, cluster.node(2).controller().postReservation(request(slot("10:40", "11:00"))).status())
                .assertAll();
    }

    @Scenario(value = "same interval on two different courts -> both 201", order = 8)
    void differentResourcesIndependent() {
        Cluster cluster = Fixtures.cluster();
        new Verdict()
                .equal(HOT_COURT, 201, cluster.node(0).controller().postReservation(request(HOT_COURT, slot("10:00", "11:00"))).status())
                .equal(OTHER_COURT, 201, cluster.node(1).controller().postReservation(request(OTHER_COURT, slot("10:00", "11:00"))).status())
                .assertAll();
    }
}

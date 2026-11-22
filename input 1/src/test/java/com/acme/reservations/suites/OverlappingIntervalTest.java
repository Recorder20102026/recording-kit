package com.acme.reservations.suites;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.domain.TimeInterval;
import com.acme.reservations.testkit.*;

import java.util.List;

import static com.acme.reservations.testkit.Fixtures.*;

/**
 * Every shape of interval overlap against an existing 10:00-11:00 booking, first sequentially
 * (pure predicate correctness) and then concurrently across pods (race correctness).
 */
public class OverlappingIntervalTest {

    private static final TimeInterval EXISTING = slot("10:00", "11:00");

    public static void main(String[] args) {
        TestRunner.runAndExit(OverlappingIntervalTest.class);
    }

    @Scenario(value = "identical interval      [10:00-11:00) is rejected", order = 1)
    void identicalIntervalRejected() {
        expectSecondBooking(slot("10:00", "11:00"), 409);
    }

    @Scenario(value = "contained interval      [10:15-10:45) is rejected", order = 2)
    void containedIntervalRejected() {
        expectSecondBooking(slot("10:15", "10:45"), 409);
    }

    @Scenario(value = "right partial overlap   [10:30-11:30) is rejected", order = 3)
    void rightPartialOverlapRejected() {
        expectSecondBooking(slot("10:30", "11:30"), 409);
    }

    @Scenario(value = "left partial overlap    [09:30-10:30) is rejected", order = 4)
    void leftPartialOverlapRejected() {
        expectSecondBooking(slot("09:30", "10:30"), 409);
    }

    @Scenario(value = "enclosing interval      [09:00-12:00) is rejected", order = 5)
    void enclosingIntervalRejected() {
        expectSecondBooking(slot("09:00", "12:00"), 409);
    }

    @Scenario(value = "touching intervals [09:00-10:00) and [11:00-12:00) are allowed (half-open)", order = 6)
    void adjacentIntervalsAllowed() {
        Cluster cluster = Fixtures.cluster();
        HttpResponse first = cluster.node(0).controller().postReservation(request(EXISTING));
        HttpResponse before = cluster.node(0).controller().postReservation(request(slot("09:00", "10:00")));
        HttpResponse after = cluster.node(0).controller().postReservation(request(slot("11:00", "12:00")));
        new Verdict()
                .equal("existing booking created", 201, first.status())
                .equal("[09:00-10:00) booked", 201, before.status())
                .equal("[11:00-12:00) booked", 201, after.status())
                .equal("double bookings", 0, Invariants.doubleBookings(cluster.db()).size())
                .assertAll();
    }

    @Scenario(value = "concurrent partial overlaps on different pods: exactly one wins (10 rounds)", order = 7)
    void concurrentPartialOverlapsAcrossNodes() {
        int roundsWithDoubleBooking = 0;
        for (int round = 0; round < 10; round++) {
            Cluster cluster = Fixtures.cluster();
            LoadRunner.fireTogether(List.of(
                    new LoadRunner.Submission(request(slot("10:00", "11:00")), "node-a",
                            cluster.node(0).controller()::postReservation),
                    new LoadRunner.Submission(request(slot("10:30", "11:30")), "node-b",
                            cluster.node(1).controller()::postReservation)));
            if (!Invariants.doubleBookings(cluster.db()).isEmpty()) {
                roundsWithDoubleBooking++;
            }
        }
        new Verdict()
                .equal("rounds that ended with a double booking", 0, roundsWithDoubleBooking)
                .assertAll();
    }

    private void expectSecondBooking(TimeInterval second, int expectedStatus) {
        Cluster cluster = Fixtures.cluster();
        HttpResponse first = cluster.node(0).controller().postReservation(request(EXISTING));
        HttpResponse response = cluster.node(0).controller().postReservation(request(second));
        new Verdict()
                .equal("existing booking " + EXISTING + " created", 201, first.status())
                .equal("second request " + second + " -> HTTP", expectedStatus, response.status())
                .equal("double bookings in DB", 0, Invariants.doubleBookings(cluster.db()).size())
                .assertAll();
    }
}

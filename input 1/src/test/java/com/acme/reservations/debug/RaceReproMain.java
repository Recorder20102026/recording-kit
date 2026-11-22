package com.acme.reservations.debug;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.domain.*;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.testkit.Fixtures;

import java.util.concurrent.*;

import static com.acme.reservations.testkit.Fixtures.*;

/**
 * Minimal reproduction of INC-2291 for the debugger: two customers, two pods, partially
 * overlapping slots, sent at the same instant.
 *
 * <p>Debug tip: put a breakpoint (Suspend: <b>Thread</b>, not All) on the first line after the
 * availability check in {@code ReservationService#createInTransaction} and run this class in Debug.
 * Both client threads stop there - both passed the check, so both will book.
 */
public final class RaceReproMain {

    public static void main(String[] args) throws Exception {
        Cluster cluster = Fixtures.cluster();
        CreateReservationRequest alice = request(slot("10:00", "11:00"));
        CreateReservationRequest bob = request(slot("10:30", "11:30"));

        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<HttpResponse> onNodeA = CompletableFuture.supplyAsync(() -> {
            await(go);
            return cluster.node(0).controller().postReservation(alice);
        });
        CompletableFuture<HttpResponse> onNodeB = CompletableFuture.supplyAsync(() -> {
            await(go);
            return cluster.node(1).controller().postReservation(bob);
        });
        go.countDown();

        print("alice -> node-a " + alice.interval(), onNodeA.get());
        print("bob   -> node-b " + bob.interval(), onNodeB.get());
        cluster.quiesce();

        System.out.println();
        System.out.println("committed reservations on " + Fixtures.HOT_COURT + ":");
        for (Reservation r : cluster.db().committedRows(Schema.RESERVATIONS)) {
            System.out.printf("  %-40s %s  %s  by %s%n", r.id(), r.interval(), r.status(), r.createdByNode());
        }
        System.out.println("card holds at payment provider: " + cluster.paymentGateway().holds().size());
        boolean doubleBooked = cluster.db().committedRows(Schema.RESERVATIONS).stream()
                .filter(Reservation::isActive).count() > 1;
        System.out.println(doubleBooked ? "\n>>> DOUBLE BOOKING reproduced" : "\n>>> no double booking");
    }

    private static void print(String who, HttpResponse r) {
        System.out.printf("%-60s HTTP %d %s%n", who, r.status(), r.body());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

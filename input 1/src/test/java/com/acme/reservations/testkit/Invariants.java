package com.acme.reservations.testkit;

import com.acme.reservations.cluster.Cluster;
import com.acme.reservations.db.Database;
import com.acme.reservations.domain.*;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.sideeffects.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** System-wide invariants checked against committed state - not against what the service claims. */
public final class Invariants {

    private Invariants() {
    }

    public record DoubleBooking(Reservation first, Reservation second) {
        @Override
        public String toString() {
            return first.id() + " " + first.interval() + " <-> " + second.id() + " " + second.interval();
        }
    }

    /** Pairs of CONFIRMED reservations on the same resource whose intervals overlap. Must always be empty. */
    public static List<DoubleBooking> doubleBookings(Database db) {
        Map<String, List<Reservation>> byResource = db.committedRows(Schema.RESERVATIONS).stream()
                .filter(Reservation::isActive)
                .collect(Collectors.groupingBy(Reservation::resourceId));
        List<DoubleBooking> result = new ArrayList<>();
        for (List<Reservation> list : byResource.values()) {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (list.get(i).interval().overlaps(list.get(j).interval())) {
                        result.add(new DoubleBooking(list.get(i), list.get(j)));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Partial-state audit after the outbox has been drained.
     *
     * @param orphanHolds     ACTIVE card holds for a reservation that does not exist / is not confirmed
     * @param duplicateHolds  extra ACTIVE holds for the same confirmed reservation (customer charged twice)
     * @param missingHolds    confirmed reservations without a hold (we gave away the slot for free)
     * @param orphanEvents    RESERVATION_CREATED events for reservations that were never committed
     * @param duplicateEvents extra RESERVATION_CREATED events for the same reservation (two e-mails)
     * @param missingEvents   confirmed reservations nobody was told about
     */
    public record SideEffectAudit(int orphanHolds, int duplicateHolds, int missingHolds,
                                  int orphanEvents, int duplicateEvents, int missingEvents) {

        public int violations() {
            return orphanHolds + duplicateHolds + missingHolds + orphanEvents + duplicateEvents + missingEvents;
        }

        public int orphans() {
            return orphanHolds + orphanEvents;
        }

        @Override
        public String toString() {
            return "orphanHolds=%d duplicateHolds=%d missingHolds=%d orphanEvents=%d duplicateEvents=%d missingEvents=%d"
                    .formatted(orphanHolds, duplicateHolds, missingHolds, orphanEvents, duplicateEvents, missingEvents);
        }
    }

    public static SideEffectAudit auditSideEffects(Cluster cluster) {
        cluster.quiesce();
        List<Reservation> all = cluster.db().committedRows(Schema.RESERVATIONS);
        Set<String> confirmed = all.stream().filter(Reservation::isActive).map(Reservation::id)
                .collect(Collectors.toSet());
        Set<String> committed = all.stream().map(Reservation::id).collect(Collectors.toSet());

        List<PaymentHold> activeHolds = cluster.paymentGateway().holds().stream()
                .filter(h -> h.status() == PaymentHold.Status.ACTIVE).toList();
        Map<String, Long> holdsPerReservation = activeHolds.stream()
                .collect(Collectors.groupingBy(PaymentHold::reservationRef, Collectors.counting()));
        int orphanHolds = (int) activeHolds.stream().filter(h -> !confirmed.contains(h.reservationRef())).count();
        int duplicateHolds = (int) confirmed.stream()
                .mapToLong(id -> Math.max(0, holdsPerReservation.getOrDefault(id, 0L) - 1)).sum();
        int missingHolds = (int) confirmed.stream().filter(id -> !holdsPerReservation.containsKey(id)).count();

        List<ReservationEvent> created = cluster.eventBus().published().stream()
                .filter(e -> e.type() == ReservationEvent.Type.RESERVATION_CREATED).toList();
        Map<String, Long> eventsPerReservation = created.stream()
                .collect(Collectors.groupingBy(ReservationEvent::reservationId, Collectors.counting()));
        int orphanEvents = (int) created.stream().filter(e -> !committed.contains(e.reservationId())).count();
        int duplicateEvents = (int) committed.stream()
                .mapToLong(id -> Math.max(0, eventsPerReservation.getOrDefault(id, 0L) - 1)).sum();
        int missingEvents = (int) confirmed.stream().filter(id -> !eventsPerReservation.containsKey(id)).count();

        return new SideEffectAudit(orphanHolds, duplicateHolds, missingHolds, orphanEvents, duplicateEvents,
                missingEvents);
    }

    /** 409s that are NOT explained by a committed overlapping reservation (the client was lied to). */
    public static int unjustifiedConflicts(Database db, List<LoadRunner.Outcome> outcomes) {
        List<Reservation> confirmed = db.committedRows(Schema.RESERVATIONS).stream()
                .filter(Reservation::isActive).toList();
        int unjustified = 0;
        for (LoadRunner.Outcome o : outcomes) {
            if (o.response().result() instanceof ReservationResult.Conflict) {
                boolean explained = confirmed.stream().anyMatch(r ->
                        r.resourceId().equals(o.request().resourceId()) && r.interval().overlaps(o.request().interval()));
                if (!explained) {
                    unjustified++;
                }
            }
        }
        return unjustified;
    }

    public static <T> Map<T, Long> countBy(List<LoadRunner.Outcome> outcomes, Function<LoadRunner.Outcome, T> key) {
        return outcomes.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }
}

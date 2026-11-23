package com.acme.reservations.cluster;

import com.acme.reservations.api.ReservationController;
import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.db.Database;
import com.acme.reservations.outbox.OutboxRelay;
import com.acme.reservations.persistence.*;
import com.acme.reservations.service.*;
import com.acme.reservations.sideeffects.*;

/**
 * One pod of reservation-service. Everything constructed here (service, locks held in its fields,
 * metrics) is private to this node. Only the {@link Database}, {@link PaymentGateway} and
 * {@link EventBus} are shared with the other pods.
 */
public final class ServiceNode {

    private final String name;
    private final ReservationService service;
    private final ReservationController controller;
    private final OutboxRelay outboxRelay;
    private final ServiceMetrics metrics;

    public ServiceNode(String name, Database db, PaymentGateway paymentGateway, EventBus eventBus,
                       ReservationConfig config) {
        this.name = name;
        this.metrics = new ServiceMetrics();
        ReservationRepository reservations = new ReservationRepository();
        ResourceRepository resources = new ResourceRepository();
        OutboxRepository outbox = new OutboxRepository(db);
        SlotAvailabilityChecker availability = new SlotAvailabilityChecker(reservations);
        LockingStrategy locking = switch (config.lockingStrategy()) {
            case "pessimistic" -> new PessimisticResourceLock(resources, availability);
            case "fenced" -> new SlotBucketFence(config.slotBucketSize());
            default -> throw new IllegalArgumentException("unknown reservation.locking.strategy: " + config.lockingStrategy());
        };
        this.service = new ReservationService(name, db, reservations, resources, outbox, availability,
                locking, RetryPolicy.fromConfig(config), metrics);
        this.controller = new ReservationController(service);
        this.outboxRelay = new OutboxRelay(db, outbox, paymentGateway, eventBus);
    }

    public String name() {
        return name;
    }

    public ReservationController controller() {
        return controller;
    }

    public ReservationService service() {
        return service;
    }

    public OutboxRelay outboxRelay() {
        return outboxRelay;
    }

    public ServiceMetrics metrics() {
        return metrics;
    }
}

package com.acme.reservations.cluster;

import com.acme.reservations.api.ReservationController;
import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.db.Database;
import com.acme.reservations.outbox.OutboxRelay;
import com.acme.reservations.persistence.OutboxRepository;
import com.acme.reservations.persistence.ReservationRepository;
import com.acme.reservations.persistence.ResourceRepository;
import com.acme.reservations.service.ReservationService;
import com.acme.reservations.service.RetryPolicy;
import com.acme.reservations.service.ServiceMetrics;
import com.acme.reservations.service.SlotAvailabilityChecker;
import com.acme.reservations.sideeffects.EventBus;
import com.acme.reservations.sideeffects.PaymentGateway;

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
        this.service = new ReservationService(name, db, reservations, resources, outbox,
                new SlotAvailabilityChecker(reservations), paymentGateway, eventBus,
                RetryPolicy.fromConfig(config), config, metrics);
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

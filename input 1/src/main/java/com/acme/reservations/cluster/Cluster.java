package com.acme.reservations.cluster;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.config.ReservationConfig;
import com.acme.reservations.db.Database;
import com.acme.reservations.domain.CreateReservationRequest;
import com.acme.reservations.domain.Resource;
import com.acme.reservations.persistence.Schema;
import com.acme.reservations.sideeffects.EventBus;
import com.acme.reservations.sideeffects.PaymentGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A horizontally scaled deployment: N stateless pods behind a round-robin load balancer,
 * all talking to one shared database, payment provider and message broker.
 */
public final class Cluster {

    private final ReservationConfig config;
    private final Database db;
    private final PaymentGateway paymentGateway;
    private final EventBus eventBus;
    private final List<ServiceNode> nodes = new ArrayList<>();
    private final AtomicInteger roundRobin = new AtomicInteger();

    private Cluster(ReservationConfig config, int nodeCount) {
        this.config = config;
        this.db = new Database(config.dbConfig());
        this.paymentGateway = new PaymentGateway(config.paymentLatencyMinMillis(), config.paymentLatencyMaxMillis());
        this.eventBus = new EventBus(config.eventBusLatencyMillis());
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new ServiceNode("node-" + (char) ('a' + i), db, paymentGateway, eventBus, config));
        }
    }

    public static Cluster start(ReservationConfig config, int nodeCount) {
        return new Cluster(config, nodeCount);
    }

    public static Cluster start(ReservationConfig config) {
        return start(config, config.clusterNodes());
    }

    public Cluster withResource(Resource resource) {
        db.seed(Schema.RESOURCES, resource.id(), resource);
        return this;
    }

    /** Load balancer: round-robin over all pods. */
    public HttpResponse post(CreateReservationRequest request) {
        return nextNode().controller().postReservation(request);
    }

    public ServiceNode nextNode() {
        return nodes.get(Math.floorMod(roundRobin.getAndIncrement(), nodes.size()));
    }

    public ServiceNode node(int index) {
        return nodes.get(index);
    }

    public List<ServiceNode> nodes() {
        return nodes;
    }

    /** Lets the outbox relay deliver everything that is pending (what the background worker does every 100ms). */
    public int quiesce() {
        int total = 0;
        int sent;
        do {
            sent = nodes.get(0).outboxRelay().drain();
            total += sent;
        } while (sent > 0);
        return total;
    }

    public Database db() {
        return db;
    }

    public PaymentGateway paymentGateway() {
        return paymentGateway;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public ReservationConfig config() {
        return config;
    }
}

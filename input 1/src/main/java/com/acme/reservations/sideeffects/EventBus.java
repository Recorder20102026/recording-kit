package com.acme.reservations.sideeffects;

import com.acme.reservations.support.Latency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Producer for the message broker (simulated). Once published, an event is out of our hands:
 * customers get an e-mail, the calendar is updated, billing starts.
 * Consumers de-duplicate on {@code eventId}.
 */
public final class EventBus {

    private final long latencyMillis;
    private final ConcurrentHashMap<String, ReservationEvent> published = new ConcurrentHashMap<>();

    public EventBus(long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    public void publish(ReservationEvent event) {
        Latency.parkJittered(latencyMillis, latencyMillis);
        published.putIfAbsent(event.eventId(), event);
    }

    public List<ReservationEvent> published() {
        return new ArrayList<>(published.values());
    }
}

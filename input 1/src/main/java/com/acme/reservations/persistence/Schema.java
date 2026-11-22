package com.acme.reservations.persistence;

import com.acme.reservations.db.TableDef;
import com.acme.reservations.domain.*;
import com.acme.reservations.outbox.OutboxMessage;

/**
 * Tables of the reservations database.
 *
 * <pre>
 * CREATE TABLE resource    (id text PRIMARY KEY, name text, hourly_rate_cents bigint, active bool,
 *                           booking_count bigint, version bigint);
 * CREATE TABLE reservation (id text PRIMARY KEY, request_id text, resource_id text REFERENCES resource,
 *                           customer_id text, starts_at timestamptz, ends_at timestamptz, status text,
 *                           amount_cents bigint, payment_hold_key text, created_by_node text, version bigint);
 * CREATE INDEX reservation_resource_time ON reservation (resource_id, starts_at);
 * CREATE TABLE outbox      (id text PRIMARY KEY, type text, payload jsonb, status text, created_at timestamptz);
 * </pre>
 */
public final class Schema {

    public static final TableDef<Resource> RESOURCES = new TableDef<>("resource");
    public static final TableDef<Reservation> RESERVATIONS = new TableDef<>("reservation");
    public static final TableDef<OutboxMessage> OUTBOX = new TableDef<>("outbox");

    private Schema() {
    }
}

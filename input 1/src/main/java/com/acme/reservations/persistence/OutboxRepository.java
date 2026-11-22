package com.acme.reservations.persistence;

import com.acme.reservations.db.*;
import com.acme.reservations.outbox.OutboxMessage;

import java.util.*;

public final class OutboxRepository {

    private final Database db;

    public OutboxRepository(Database db) {
        this.db = db;
    }

    /** {@code INSERT INTO outbox (id, type, payload, status) VALUES (nextval('outbox_seq'), ...)} */
    public void enqueue(Transaction tx, OutboxMessage.Type type, String reservationId, String resourceId,
                        String customerId, long amountCents, String idempotencyKey) {
        OutboxMessage message = OutboxMessage.pending(db.nextVal("outbox_seq"), type, reservationId, resourceId,
                customerId, amountCents, idempotencyKey);
        tx.insert(Schema.OUTBOX, message.id(), message);
    }

    /** {@code SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY id} */
    public List<OutboxMessage> findPending(Transaction tx) {
        return tx.selectWhere(Schema.OUTBOX, m -> m.status() == OutboxMessage.Status.PENDING).stream()
                .sorted(Comparator.comparingLong(OutboxMessage::sequence))
                .toList();
    }

    /** {@code SELECT * FROM outbox WHERE id = ? FOR UPDATE SKIP LOCKED} (simplified to FOR UPDATE) */
    public java.util.Optional<OutboxMessage> lock(Transaction tx, String id) {
        return tx.selectForUpdate(Schema.OUTBOX, id);
    }

    /** {@code UPDATE outbox SET status = 'SENT' WHERE id = ?} */
    public void markSent(Transaction tx, String id) {
        tx.update(Schema.OUTBOX, id, OutboxMessage::sent);
    }
}

package com.acme.reservations.db;

import com.acme.reservations.support.Latency;

import java.util.*;
import java.util.function.*;

/**
 * A READ COMMITTED transaction against the shared database.
 *
 * <p>Semantics deliberately mirror PostgreSQL:
 * <ul>
 *   <li>Plain reads never block and see the latest committed data plus this transaction's own writes.</li>
 *   <li>Writes take an exclusive row lock that is held until COMMIT / ROLLBACK.</li>
 *   <li>{@code SELECT ... FOR UPDATE} on a key that does not exist locks <b>nothing</b>.</li>
 *   <li>Every lock wait is bounded by {@code lock_timeout}; exceeding it throws {@link LockWaitTimeoutException}.</li>
 * </ul>
 * Each method costs one simulated network round trip (one SQL statement).
 */
public final class Transaction {

    private enum State { ACTIVE, COMMITTED, ROLLED_BACK }

    private record Staged(TableDef<?> table, String key, Object value) {
    }

    private final Database db;
    private final long id;
    private final String label;
    private final Map<String, Staged> writes = new LinkedHashMap<>();
    private final Set<String> heldLocks = new LinkedHashSet<>();
    private State state = State.ACTIVE;

    Transaction(Database db, long id, String label) {
        this.db = db;
        this.id = id;
        this.label = label;
    }

    public long id() {
        return id;
    }

    public String label() {
        return label;
    }

    // ------------------------------------------------------------------ reads

    /** {@code SELECT * FROM t WHERE pk = :key} (non-locking). */
    public <V> Optional<V> select(TableDef<V> table, String key) {
        statement();
        return Optional.ofNullable(read(table, key));
    }

    /** {@code SELECT * FROM t WHERE <predicate>} (non-locking). */
    public <V> List<V> selectWhere(TableDef<V> table, Predicate<V> predicate) {
        statement();
        Map<String, V> merged = new LinkedHashMap<>(db.committedTable(table));
        for (Staged staged : writes.values()) {
            if (staged.table().equals(table)) {
                merged.put(staged.key(), cast(staged.value()));
            }
        }
        List<V> result = new ArrayList<>();
        for (V row : merged.values()) {
            if (predicate.test(row)) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * {@code SELECT * FROM t WHERE pk = :key FOR UPDATE}.
     * <p>If the row does not exist nothing is locked and an empty result is returned -
     * exactly like PostgreSQL. You cannot lock a row that is not there.
     */
    public <V> Optional<V> selectForUpdate(TableDef<V> table, String key) {
        statement();
        if (read(table, key) == null) {
            return Optional.empty();
        }
        lock(table.lockKey(key));
        return Optional.ofNullable(read(table, key));
    }

    // ----------------------------------------------------------------- writes

    /** {@code INSERT INTO t VALUES (...)} - fails with {@link DuplicateKeyException} if the key exists. */
    public <V> void insert(TableDef<V> table, String key, V row) {
        statement();
        lock(table.lockKey(key));
        if (read(table, key) != null) {
            throw new DuplicateKeyException(table.name(), key);
        }
        stage(table, key, row);
    }

    /** {@code UPDATE t SET ... WHERE pk = :key} computed from the current row. Returns the new row. */
    public <V> V update(TableDef<V> table, String key, UnaryOperator<V> change) {
        statement();
        lock(table.lockKey(key));
        V current = read(table, key);
        if (current == null) {
            throw new IllegalStateException("UPDATE 0: no row " + table.name() + "/" + key);
        }
        V updated = change.apply(current);
        stage(table, key, updated);
        return updated;
    }

    /**
     * {@code UPDATE t SET ... WHERE pk = :key AND version = :expected} (optimistic concurrency).
     *
     * @return {@code true} if the row was updated, {@code false} if the version did not match
     */
    public <V> boolean updateIfVersion(TableDef<V> table, String key, long expectedVersion,
                                       ToLongFunction<V> versionOf, UnaryOperator<V> change) {
        statement();
        lock(table.lockKey(key));
        V current = read(table, key);
        if (current == null || versionOf.applyAsLong(current) != expectedVersion) {
            return false;
        }
        stage(table, key, change.apply(current));
        return true;
    }

    /**
     * Multi-row compare-and-set in one statement:
     * {@code UPDATE t SET ... WHERE (pk, version) IN ((:k1, :v1), (:k2, :v2), ...)}.
     *
     * @return number of rows updated (rows whose version moved are skipped)
     */
    public <V> int updateWhereVersion(TableDef<V> table, Map<String, Long> expectedVersions,
                                      ToLongFunction<V> versionOf, UnaryOperator<V> change) {
        statement();
        int updated = 0;
        for (Map.Entry<String, Long> entry : expectedVersions.entrySet()) {
            lock(table.lockKey(entry.getKey()));
            V current = read(table, entry.getKey());
            if (current != null && versionOf.applyAsLong(current) == entry.getValue()) {
                stage(table, entry.getKey(), change.apply(current));
                updated++;
            }
        }
        return updated;
    }

    /**
     * Upsert-and-lock several rows in one statement, in the order given:
     * <pre>
     * INSERT INTO t (pk, ...) VALUES (:k1, ...), (:k2, ...)
     * ON CONFLICT (pk) DO UPDATE SET pk = EXCLUDED.pk   -- no-op update => row lock
     * RETURNING *;
     * </pre>
     * Rows that do not exist yet are created, so the lock is taken even on "empty" keys.
     * Callers must pass keys in a canonical (sorted) order to avoid deadlocks.
     */
    public <V> List<V> upsertAllForUpdate(TableDef<V> table, List<String> keys, Function<String, V> initialRow) {
        return upsertAllForUpdate(table, keys, initialRow, row -> false);
    }

    /**
     * Same as {@link #upsertAllForUpdate(TableDef, List, Function)} but evaluated server-side row by row
     * (a PL/pgSQL function in production) and stops right after locking the first row for which
     * {@code stopAfter} is true. Lets a doomed request release its locks without queueing for the rest.
     * <pre>
     * SELECT * FROM lock_rows_until(:table, ARRAY[:k1, :k2, ...], :stop_condition);
     * </pre>
     *
     * @return the rows locked, in order; if it stopped early the last element matched {@code stopAfter}
     */
    public <V> List<V> upsertAllForUpdate(TableDef<V> table, List<String> keys, Function<String, V> initialRow,
                                          Predicate<V> stopAfter) {
        statement();
        List<V> rows = new ArrayList<>(keys.size());
        for (String key : keys) {
            lock(table.lockKey(key));
            V current = read(table, key);
            if (current == null) {
                current = initialRow.apply(key);
                stage(table, key, current);
            }
            rows.add(current);
            if (stopAfter.test(current)) {
                break;
            }
        }
        return rows;
    }

    // -------------------------------------------------------------- lifecycle

    public void commit() {
        ensureActive();
        try {
            db.faults().beforeCommit(this);
            // read-only COMMIT = one round trip; COMMIT with writes = WAL flush
            Latency.parkMicros(writes.isEmpty() ? db.config().statementLatencyMicros() : db.config().commitLatencyMicros());
            db.apply(writes.values().stream().map(s -> new Database.Write(s.table(), s.key(), s.value())).toList());
            state = State.COMMITTED;
            db.metrics().commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } finally {
            if (state == State.COMMITTED) {
                releaseLocks();
            }
        }
        db.faults().afterCommit(this);
    }

    public void rollback() {
        if (state != State.ACTIVE) {
            return;
        }
        state = State.ROLLED_BACK;
        writes.clear();
        db.metrics().rollback();
        releaseLocks();
    }

    // ---------------------------------------------------------------- helpers

    private void statement() {
        ensureActive();
        db.metrics().statement();
        Latency.parkMicros(db.config().statementLatencyMicros());
    }

    private void lock(String lockKey) {
        if (heldLocks.contains(lockKey)) {
            return;
        }
        db.locks().acquire(lockKey, id, db.config().lockWaitTimeout().toNanos());
        heldLocks.add(lockKey);
    }

    private void releaseLocks() {
        for (String lockKey : heldLocks) {
            db.locks().release(lockKey, id);
        }
        heldLocks.clear();
    }

    private <V> V read(TableDef<V> table, String key) {
        Staged staged = writes.get(table.lockKey(key));
        if (staged != null) {
            return cast(staged.value());
        }
        return db.committedTable(table).get(key);
    }

    private <V> void stage(TableDef<V> table, String key, V row) {
        writes.put(table.lockKey(key), new Staged(table, key, row));
    }

    private void ensureActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("transaction " + id + " is " + state);
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> V cast(Object value) {
        return (V) value;
    }
}

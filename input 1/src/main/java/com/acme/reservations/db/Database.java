package com.acme.reservations.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * In-process stand-in for the shared PostgreSQL primary used by all reservation-service pods.
 *
 * <p>One instance is shared by every {@code ServiceNode} in a {@code Cluster}; anything stored or
 * locked here is visible cluster-wide. Anything a node keeps in its own fields is NOT.
 */
public final class Database {

    record Write(TableDef<?> table, String key, Object value) {
    }

    private final DbConfig config;
    private final DbMetrics metrics = new DbMetrics();
    private final FaultInjector faults = new FaultInjector();
    private final LockManager locks = new LockManager(metrics);
    private final Map<String, ConcurrentHashMap<String, Object>> tables = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final AtomicLong txIds = new AtomicLong();
    private final Object commitMutex = new Object();

    public Database(DbConfig config) {
        this.config = config;
    }

    /** Runs {@code work} in a transaction; commits on return, rolls back on any exception. */
    public <T> T inTransaction(String label, Function<Transaction, T> work) {
        Transaction tx = begin(label);
        T result;
        try {
            result = work.apply(tx);
        } catch (RuntimeException e) {
            tx.rollback();
            throw e;
        }
        tx.commit();
        return result;
    }

    public Transaction begin(String label) {
        return new Transaction(this, txIds.incrementAndGet(), label);
    }

    /** {@code SELECT nextval('name')} - non-transactional, like a PostgreSQL sequence. */
    public long nextVal(String sequence) {
        return sequences.computeIfAbsent(sequence, s -> new AtomicLong()).incrementAndGet();
    }

    // ---------------------------------------------------- admin / test access

    /** Direct committed read with no latency - for fixtures, auditors and dashboards. */
    public <V> Optional<V> committed(TableDef<V> table, String key) {
        return Optional.ofNullable(committedTable(table).get(key));
    }

    public <V> List<V> committedRows(TableDef<V> table) {
        return Collections.unmodifiableList(new ArrayList<>(committedTable(table).values()));
    }

    public <V> void seed(TableDef<V> table, String key, V row) {
        synchronized (commitMutex) {
            rawTable(table).put(key, row);
        }
    }

    public DbMetrics metrics() {
        return metrics;
    }

    public FaultInjector faults() {
        return faults;
    }

    public DbConfig config() {
        return config;
    }

    // ---------------------------------------------------------------- internal

    LockManager locks() {
        return locks;
    }

    @SuppressWarnings("unchecked")
    <V> Map<String, V> committedTable(TableDef<V> table) {
        return (Map<String, V>) (Map<String, ?>) rawTable(table);
    }

    private ConcurrentHashMap<String, Object> rawTable(TableDef<?> table) {
        return tables.computeIfAbsent(table.name(), n -> new ConcurrentHashMap<>());
    }

    void apply(List<Write> writes) {
        synchronized (commitMutex) {
            for (Write write : writes) {
                rawTable(write.table()).put(write.key(), write.value());
            }
        }
    }
}

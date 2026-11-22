package com.acme.reservations.db;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/** pg_stat-style counters for the shared database. */
public final class DbMetrics {

    private final LongAdder statements = new LongAdder();
    private final LongAdder commits = new LongAdder();
    private final LongAdder rollbacks = new LongAdder();
    private final LongAdder lockTimeouts = new LongAdder();
    private final ConcurrentLinkedQueue<Long> contendedLockWaitsNanos = new ConcurrentLinkedQueue<>();

    void statement() {
        statements.increment();
    }

    void commit() {
        commits.increment();
    }

    void rollback() {
        rollbacks.increment();
    }

    void lockWait(long nanos) {
        contendedLockWaitsNanos.add(nanos);
    }

    void lockTimeout(long nanos) {
        lockTimeouts.increment();
        contendedLockWaitsNanos.add(nanos);
    }

    public Snapshot snapshot() {
        List<Long> waits = new ArrayList<>(contendedLockWaitsNanos);
        Collections.sort(waits);
        long max = waits.isEmpty() ? 0 : waits.get(waits.size() - 1);
        long p99 = waits.isEmpty() ? 0 : waits.get(Math.min(waits.size() - 1, (int) Math.ceil(waits.size() * 0.99) - 1));
        return new Snapshot(statements.sum(), commits.sum(), rollbacks.sum(), lockTimeouts.sum(),
                waits.size(), max, p99);
    }

    /**
     * @param contendedLockWaits number of lock acquisitions that had to wait (incl. ones that timed out)
     * @param maxLockWaitNanos   longest single lock wait observed
     */
    public record Snapshot(long statements, long commits, long rollbacks, long lockTimeouts,
                           long contendedLockWaits, long maxLockWaitNanos, long p99LockWaitNanos) {

        public double maxLockWaitMillis() {
            return maxLockWaitNanos / 1_000_000.0;
        }

        public double p99LockWaitMillis() {
            return p99LockWaitNanos / 1_000_000.0;
        }
    }
}

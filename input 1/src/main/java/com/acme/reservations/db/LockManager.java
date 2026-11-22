package com.acme.reservations.db;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Exclusive row / index-entry locks held until COMMIT or ROLLBACK, with a
 * {@code lock_timeout} on every wait. Waiters are granted the lock in FIFO order
 * (like PostgreSQL's lock queue). Lives inside the shared database, so it is visible
 * to every service node - unlike a JVM-local lock.
 */
final class LockManager {

    private static final class Waiter {
        final long txId;
        final Thread thread = Thread.currentThread();
        volatile boolean granted;

        Waiter(long txId) {
            this.txId = txId;
        }
    }

    private static final class RowLock {
        long ownerTxId;
        final ArrayDeque<Waiter> queue = new ArrayDeque<>();
    }

    private static final long MAX_PARK_SLICE_NANOS = 500_000;

    private final ConcurrentHashMap<String, RowLock> locks = new ConcurrentHashMap<>();
    private final DbMetrics metrics;

    LockManager(DbMetrics metrics) {
        this.metrics = metrics;
    }

    void acquire(String lockKey, long txId, long timeoutNanos) {
        RowLock lock = locks.computeIfAbsent(lockKey, k -> new RowLock());
        Waiter waiter;
        synchronized (lock) {
            if (lock.ownerTxId == txId) {
                return;
            }
            if (lock.ownerTxId == 0) {
                lock.ownerTxId = txId;
                return;
            }
            waiter = new Waiter(txId);
            lock.queue.addLast(waiter);
        }
        long start = System.nanoTime();
        long deadline = start + timeoutNanos;
        while (!waiter.granted) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                synchronized (lock) {
                    if (waiter.granted) {
                        break;
                    }
                    lock.queue.remove(waiter);
                }
                long waited = System.nanoTime() - start;
                metrics.lockTimeout(waited);
                throw new LockWaitTimeoutException(lockKey, waited);
            }
            // short slices: the OS may coalesce long timed parks by several ms, lock_timeout must be precise
            LockSupport.parkNanos(this, Math.min(remaining, MAX_PARK_SLICE_NANOS));
            if (Thread.interrupted()) {
                synchronized (lock) {
                    if (!waiter.granted) {
                        lock.queue.remove(waiter);
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while waiting for " + lockKey);
                    }
                }
            }
        }
        metrics.lockWait(System.nanoTime() - start);
    }

    void release(String lockKey, long txId) {
        RowLock lock = locks.get(lockKey);
        if (lock == null) {
            return;
        }
        synchronized (lock) {
            if (lock.ownerTxId != txId) {
                return;
            }
            Waiter next = lock.queue.pollFirst();
            if (next == null) {
                lock.ownerTxId = 0;
                return;
            }
            lock.ownerTxId = next.txId;
            next.granted = true;
            LockSupport.unpark(next.thread);
        }
    }
}

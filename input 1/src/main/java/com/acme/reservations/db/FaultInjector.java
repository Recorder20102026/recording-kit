package com.acme.reservations.db;

import com.acme.reservations.support.Latency;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chaos hooks used by the test-suite to reproduce production failure modes
 * (connection reset on COMMIT, lost COMMIT acknowledgement, stalled lock holder).
 * Faults only fire for transactions whose label matches the target label.
 */
public final class FaultInjector {

    private volatile String targetLabel = "";
    private final AtomicInteger failBeforeCommit = new AtomicInteger();
    private final AtomicInteger loseCommitAck = new AtomicInteger();
    private final AtomicInteger stallBeforeCommit = new AtomicInteger();
    private volatile Duration stallDuration = Duration.ZERO;

    /** The next {@code n} COMMITs of {@code label} transactions fail; nothing is written. */
    public void failNextCommits(String label, int n) {
        targetLabel = label;
        failBeforeCommit.set(n);
    }

    /** The next {@code n} COMMITs of {@code label} transactions succeed but the client sees an error. */
    public void loseNextCommitAcks(String label, int n) {
        targetLabel = label;
        loseCommitAck.set(n);
    }

    /** The next {@code n} {@code label} transactions stall right before COMMIT while still holding their locks. */
    public void stallNextCommits(String label, int n, Duration stall) {
        targetLabel = label;
        stallDuration = stall;
        stallBeforeCommit.set(n);
    }

    public void reset() {
        failBeforeCommit.set(0);
        loseCommitAck.set(0);
        stallBeforeCommit.set(0);
    }

    void beforeCommit(Transaction tx) {
        if (!tx.label().equals(targetLabel)) {
            return;
        }
        if (consume(stallBeforeCommit)) {
            Latency.park(stallDuration);
        }
        if (consume(failBeforeCommit)) {
            throw new TransientDbException("FATAL: connection reset by peer during COMMIT (tx " + tx.id() + ")");
        }
    }

    void afterCommit(Transaction tx) {
        if (tx.label().equals(targetLabel) && consume(loseCommitAck)) {
            throw new TransientDbException("I/O error: COMMIT acknowledgement lost (tx " + tx.id() + " was durable)");
        }
    }

    private static boolean consume(AtomicInteger counter) {
        int current;
        do {
            current = counter.get();
            if (current <= 0) {
                return false;
            }
        } while (!counter.compareAndSet(current, current - 1));
        return true;
    }
}

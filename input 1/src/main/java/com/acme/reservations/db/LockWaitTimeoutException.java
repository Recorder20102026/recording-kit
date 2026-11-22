package com.acme.reservations.db;

/**
 * PostgreSQL {@code 55P03 lock_not_available}: the statement waited longer than
 * {@code lock_timeout} for a row lock and was cancelled. The transaction is rolled back.
 */
public class LockWaitTimeoutException extends RuntimeException {

    private final String lockKey;

    public LockWaitTimeoutException(String lockKey, long waitedNanos) {
        super("lock_timeout: waited %.1f ms for row lock on %s".formatted(waitedNanos / 1_000_000.0, lockKey));
        this.lockKey = lockKey;
    }

    public String lockKey() {
        return lockKey;
    }
}

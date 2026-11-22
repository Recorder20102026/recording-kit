package com.acme.reservations.db;

/**
 * A transient database failure (connection reset during COMMIT, failover,
 * {@code 40001 serialization_failure}). Safe to retry the whole transaction.
 */
public class TransientDbException extends RuntimeException {

    public TransientDbException(String message) {
        super(message);
    }
}

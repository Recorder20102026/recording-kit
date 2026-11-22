package com.acme.reservations.db;

/** PostgreSQL {@code 23505 unique_violation}. */
public class DuplicateKeyException extends RuntimeException {

    public DuplicateKeyException(String table, String key) {
        super("duplicate key value violates unique constraint \"" + table + "_pkey\": " + key);
    }
}

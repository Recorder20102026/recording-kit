package com.acme.reservations.db;

/** A table in the shared database. {@code V} is the row type. */
public record TableDef<V>(String name) {

    String lockKey(String primaryKey) {
        return name + ":" + primaryKey;
    }
}

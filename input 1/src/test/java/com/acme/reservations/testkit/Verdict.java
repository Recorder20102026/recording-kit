package com.acme.reservations.testkit;

import java.util.*;

/** Soft assertions: every check is printed, the scenario fails at the end if any check failed. */
public final class Verdict {

    private final List<String> failures = new ArrayList<>();

    public Verdict check(String expectation, boolean ok, String actual) {
        System.out.printf("      %s %-62s %s%n", ok ? "✔" : "✘", expectation, actual);
        if (!ok) {
            failures.add(expectation + " (actual: " + actual + ")");
        }
        return this;
    }

    public Verdict equal(String expectation, long expected, long actual) {
        return check(expectation, expected == actual, "expected " + expected + ", got " + actual);
    }

    public Verdict atMost(String expectation, double limit, double actual, String unit) {
        return check(expectation, actual <= limit, "%.2f%s (limit %.2f%s)".formatted(actual, unit, limit, unit));
    }

    public void assertAll() {
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " check(s) failed: " + String.join("; ", failures));
        }
    }
}

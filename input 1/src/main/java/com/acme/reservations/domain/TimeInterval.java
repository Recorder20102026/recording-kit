package com.acme.reservations.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A half-open booking interval {@code [start, end)}. */
public record TimeInterval(Instant start, Instant end) {

    public TimeInterval {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    public static TimeInterval of(String isoStart, String isoEnd) {
        return new TimeInterval(Instant.parse(isoStart), Instant.parse(isoEnd));
    }

    public boolean isValid() {
        return end.isAfter(start);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    /**
     * Two half-open intervals overlap iff each one starts before the other ends.
     * Touching intervals ({@code [09:00,10:00)} and {@code [10:00,11:00)}) do NOT overlap.
     */
    public boolean overlaps(TimeInterval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    @Override
    public String toString() {
        return "[" + start + " .. " + end + ")";
    }
}

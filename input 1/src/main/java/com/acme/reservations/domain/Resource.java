package com.acme.reservations.domain;

/**
 * Row of the {@code resource} table - a bookable court / room / machine.
 *
 * @param bookingCount denormalised counter shown on the ops dashboard
 */
public record Resource(String id, String name, long hourlyRateCents, boolean active, long bookingCount, long version) {

    public static Resource active(String id, String name, long hourlyRateCents) {
        return new Resource(id, name, hourlyRateCents, true, 0, 0);
    }

    public Resource withBookingRecorded() {
        return new Resource(id, name, hourlyRateCents, active, bookingCount + 1, version + 1);
    }

    public long priceFor(TimeInterval interval) {
        return hourlyRateCents * interval.duration().toMinutes() / 60;
    }
}

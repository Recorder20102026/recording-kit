package com.acme.reservations.evaluation.spikes;

import com.acme.reservations.db.Database;
import com.acme.reservations.domain.*;
import com.acme.reservations.service.ServiceMetrics;

/**
 * A candidate concurrency-control design for the create-reservation critical section.
 * Spikes are deliberately minimal: same overlap predicate (correct), same writes, same bounded
 * retry (2 retries, jittered) - ONLY the locking strategy differs, so the comparison is fair.
 */
public interface LockingDesign {

    String code();

    String title();

    /** Which platform constraint this design is expected to run into. */
    String constraintUnderTest();

    /** One instance per pod: anything held in the returned object is JVM-local to that pod. */
    Pod newPod(String nodeName, Database db, ServiceMetrics metrics);

    interface Pod {
        ReservationResult book(CreateReservationRequest request);
    }
}

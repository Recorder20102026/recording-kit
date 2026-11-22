package com.acme.reservations.evaluation.spikes;

import com.acme.reservations.db.Database;
import com.acme.reservations.domain.Reservation;
import com.acme.reservations.service.ServiceMetrics;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Design A - per-resource {@link ReentrantLock} inside the JVM (what RES-212 shipped), now with the
 * correct overlap predicate. Serializes requests that hit the SAME pod; pods do not see each other's locks.
 */
public final class JvmLocalLockDesign implements LockingDesign {

    @Override
    public String code() {
        return "A";
    }

    @Override
    public String title() {
        return "JVM-local per-resource lock";
    }

    @Override
    public String constraintUnderTest() {
        return "(a) horizontal multi-instance deployment";
    }

    @Override
    public Pod newPod(String nodeName, Database db, ServiceMetrics metrics) {
        ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        return request -> {
            ReentrantLock lock = locks.computeIfAbsent(request.resourceId(), id -> new ReentrantLock());
            lock.lock();
            try {
                return SpikeSupport.withBoundedRetry(db, metrics, "spike.A", tx -> attempt -> {
                    Optional<com.acme.reservations.domain.ReservationResult> replay = SpikeSupport.preamble(tx, request, attempt);
                    if (replay.isPresent()) {
                        return replay.get();
                    }
                    Optional<Reservation> clash = SpikeSupport.findConflict(tx, request);
                    if (clash.isPresent()) {
                        return SpikeSupport.conflict(request, clash.get(), attempt);
                    }
                    return SpikeSupport.insertBooking(tx, db, request, nodeName, attempt);
                });
            } finally {
                lock.unlock();
            }
        };
    }
}

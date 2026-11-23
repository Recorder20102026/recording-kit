# Reference patch: candidate locking structures for reservation-service

Platform reference for overlapping-interval double bookings in a multi-pod service whose database enforces
`lock_timeout = 50ms`. It holds the larger locking structures, at the same paths as in the service. The
service-specific parts are **not** included and are written in the service itself:

* the race / 20x burst harness, which extends the service's existing `testkit`;
* the bounded retry wiring (`RetryPolicy`, `ReservationConfig`, `config/reservation.properties`);
* the integration in `ServiceNode`, and the overlap predicate in `SlotAvailabilityChecker`.

`reference.patch` is the same set of files as one diff against the baseline:

```bash
patch -p1 < reference.patch     # from the service root
```

## Files

| File | Change | |
|---|---|---|
| `src/main/java/.../service/LockingStrategy.java` | Strategy interface: `acquire` → `Lease.clash()` / `Lease.claim()`, `release` | new |
| `src/main/java/.../service/PessimisticResourceLock.java` | **Candidate 1 (pessimistic):** `SELECT ... FOR UPDATE` on the parent resource row, overlap re-check under the lock | new |
| `src/main/java/.../service/SlotBucketFence.java` | **Candidate 2 (fenced):** upsert + lock only the time buckets the interval touches, sorted, early stop; version-guarded claim (fencing token) | new |
| `src/main/java/.../persistence/SlotBucket.java` | `slot_bucket` row: version (fencing token) + occupants | new |
| `src/main/java/.../persistence/Schema.java` | `+ SLOT_BUCKETS` table | changed |
| `src/main/java/.../service/ReservationService.java` | Create path driven by a `LockingStrategy`: idempotent id, lock-free fast path, strategy lease, conditional insert, outbox for side effects. Calls `retryPolicy.execute(attempt, onRetry)` | changed |
| `docs/adr/ADR-007-reservation-locking.md` | Decision record template with the measured comparison | new |

## Contract the service has to provide

`ReservationService` expects:

* `RetryPolicy.execute(IntFunction<ReservationResult> attempt, Runnable onRetry)` returning a `ReservationResult`;
* the constructor `(nodeName, db, reservations, resources, outbox, availability, LockingStrategy, RetryPolicy, metrics)`.

`SlotBucketFence` takes the bucket size as a `Duration`. Both candidates keep their locks in the shared
database, so both are multi-instance safe, and both always have a row to lock (the parent resource row, or an
upserted bucket row), so both cover partial overlaps against rows that do not exist yet. They differ in lock
granularity, which decides lock-wait timeouts and retry amplification under burst: measure both on the same
workload before choosing.

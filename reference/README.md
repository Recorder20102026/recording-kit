# Reference patch: candidate locking structures for reservation-service

Reusable building blocks for fixing overlapping-interval double bookings in a multi-pod reservation
service whose database enforces `lock_timeout = 50ms` and whose platform allows at most 2 retries.
Files are at the same paths as in the service (`src/...`, `scripts/...`, `docs/...`), so each one can be
copied as-is. `reference.patch` has all of them as one diff against the baseline.

## What's in the patch

| File | Block | Reuse |
|---|---|---|
| `src/main/java/.../service/LockingStrategy.java` | Strategy interface: `acquire` → `Lease.clash()` / `Lease.claim()`, `release` | new file |
| `src/main/java/.../service/PessimisticResourceLock.java` | **Candidate 1 (pessimistic):** `SELECT ... FOR UPDATE` on the parent resource row, overlap re-check under the lock | new file |
| `src/main/java/.../service/SlotBucketFence.java` | **Candidate 2 (fenced):** upsert + lock only the time buckets the interval touches, sorted, early stop; version-guarded claim (fencing token) | new file |
| `src/main/java/.../persistence/SlotBucket.java` | `slot_bucket` row: version (fencing token) + occupants | new file |
| `src/main/java/.../persistence/Schema.java` | `+ SLOT_BUCKETS` table | replace |
| `src/main/java/.../service/RetryPolicy.java` | Bounded retry: only `55P03` / transient errors, hard cap 2 retries, full-jitter backoff, 503 + Retry-After on exhaustion | replace |
| `src/main/java/.../service/ReservationService.java` | Create path driven by a `LockingStrategy`: idempotent id, lock-free fast path, strategy lease, conditional insert, outbox for side effects | replace |
| `src/main/java/.../config/ReservationConfig.java` | New keys: `reservation.locking.strategy`, `retry.backoff.base.ms`, `retry.backoff.max.ms`, `retry.client.retry-after.ms`, `slot.bucket.minutes` | replace |
| `src/test/java/.../testkit/LockingBenchmark.java` | Race / 20x burst harness: overlap race across pods + burst rounds, counters for double bookings, lock-wait timeouts, max wait, retries, attempts, amplification, latency | new file |
| `scripts/benchmark.sh` | `./scripts/benchmark.sh [strategy ...]` | new file |
| `docs/adr/ADR-007-reservation-locking.md` | Decision record template with the measured comparison | new file |

## Not in the patch (service-specific)

* **Integration:** choosing the `LockingStrategy` from `reservation.locking.strategy` and passing it to
  `ReservationService` (in this service: `cluster/ServiceNode.java`). Until this is wired, `ServiceNode`
  does not compile against the new `ReservationService` constructor.
* **Timeout / retry wiring:** the values in `config/reservation.properties` (strategy, retry budget,
  backoff, Retry-After, bucket size). The 50 ms `lock_timeout` is the DBA setting `db.lock.wait.timeout.ms`.
* **Constraint assertions:** `LockingBenchmark.checkConstraints` returns `null` until the service's hard
  constraints are asserted there.
* **Overlap predicate:** the service's own availability check must use a half-open overlap test.

## Choosing between the candidates

Run both on the same workload and compare the counters against the constraints:

```bash
./scripts/benchmark.sh pessimistic fenced
```

Both keep their locks in the shared database, so both are multi-instance safe, and both always have a
row to lock (the parent resource row, or an upserted bucket row), so both cover partial overlaps against
rows that do not exist yet. They differ in lock granularity, which is what decides lock-wait timeouts and
retry amplification under burst.

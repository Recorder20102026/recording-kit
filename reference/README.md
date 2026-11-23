# Reference patch: locking strategy for reservation-service

Complete reference solution for overlapping-interval double bookings in a multi-pod reservation service
whose database enforces `lock_timeout = 50ms` and whose platform allows at most 2 retries. It contains the
final version of **every** changed file, at the same paths as in the service (`src/...`, `config/...`,
`scripts/...`, `docs/...`). `reference.patch` is the same change as one diff against the baseline:

```bash
patch -p1 < reference.patch && chmod +x scripts/benchmark.sh    # from the service root
```

## Candidate locking structures

| File | Change | |
|---|---|---|
| `src/main/java/.../service/LockingStrategy.java` | Strategy interface: `acquire` → `Lease.clash()` / `Lease.claim()`, `release` | new |
| `src/main/java/.../service/PessimisticResourceLock.java` | **Candidate 1 (pessimistic):** `SELECT ... FOR UPDATE` on the parent resource row, overlap re-check under the lock | new |
| `src/main/java/.../service/SlotBucketFence.java` | **Candidate 2 (fenced):** upsert + lock only the time buckets the interval touches, sorted, early stop; version-guarded claim (fencing token) | new |
| `src/main/java/.../persistence/SlotBucket.java` | `slot_bucket` row: version (fencing token) + occupants | new |
| `src/main/java/.../persistence/Schema.java` | `+ SLOT_BUCKETS` table | changed |
| `src/main/java/.../service/RetryPolicy.java` | Bounded retry: only `55P03` / transient errors, hard cap 2 retries, full-jitter backoff, 503 + Retry-After on exhaustion | changed |
| `src/main/java/.../service/ReservationService.java` | Create path driven by a `LockingStrategy`: idempotent id, lock-free fast path, strategy lease, conditional insert, outbox for side effects | changed |
| `src/main/java/.../config/ReservationConfig.java` | New keys: `reservation.locking.strategy`, `retry.backoff.base.ms`, `retry.backoff.max.ms`, `retry.client.retry-after.ms`, `slot.bucket.minutes` | changed |

## Service integration and settings

| File | Change | |
|---|---|---|
| `src/main/java/.../service/SlotAvailabilityChecker.java` | Overlap predicate → half-open `TimeInterval.overlaps` (catches left-partial and enclosing overlaps) | changed |
| `src/main/java/.../cluster/ServiceNode.java` | Integration: each pod builds its `LockingStrategy` from `reservation.locking.strategy` (`pessimistic` / `fenced`) and passes it to `ReservationService` | changed |
| `config/reservation.properties` | Timeout / retry wiring: JVM-local lock removed, `reservation.locking.strategy`, `retry.max.retries=2`, jittered backoff 2-20 ms, Retry-After 200 ms, 15-min buckets. The 50 ms `lock_timeout` stays `db.lock.wait.timeout.ms=50` | changed |

## Benchmark harness

| File | Change | |
|---|---|---|
| `src/test/java/.../testkit/LockingBenchmark.java` | Race / 20x burst harness: overlap race across pods + burst rounds; counters for double bookings, lock-wait timeouts, max wait, retries, attempts, amplification, latency; `checkConstraints` asserts the hard constraints | new |
| `scripts/benchmark.sh` | `./scripts/benchmark.sh [strategy ...]` | new |
| `docs/adr/ADR-007-reservation-locking.md` | Decision record with the measured comparison | new |

## Choosing between the candidates

Run both on the same workload and compare the counters against the constraints:

```bash
./scripts/benchmark.sh pessimistic fenced
```

Both keep their locks in the shared database, so both are multi-instance safe, and both always have a
row to lock (the parent resource row, or an upserted bucket row), so both cover partial overlaps against
rows that do not exist yet. They differ in lock granularity, which is what decides lock-wait timeouts and
retry amplification under burst. The shipped `config/reservation.properties` selects `fenced`.

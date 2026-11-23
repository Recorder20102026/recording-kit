# ADR-007: Locking strategy for the reservation write path

* Status: accepted
* Context: [INC-2291](../INCIDENT-2291.md)
* Evidence: `reports/` (`./scripts/test.sh`, `./scripts/benchmark.sh pessimistic fenced`)

## Context

Baseline (`./scripts/test.sh`: 17 passed, 10 failed; `./scripts/benchmark.sh`: 10/10 race rounds and every
burst round end with double bookings) has five defects on the create path:

| # | Defect | Where | Proven by |
|---|---|---|---|
| D1 | Overlap predicate only checks "starts during an existing booking"; misses left-partial and enclosing overlaps. | `SlotAvailabilityChecker.findConflict` | `overlap` suite |
| D2 | Check-then-insert race: the availability check is a plain READ COMMITTED read. | `ReservationService.createInTransaction` | `overlap`, `burst`, `multinode`, benchmark overlap race |
| D3 | The RES-212 fix is a JVM-local `ReentrantLock`; it serializes one pod, not the cluster. | `ReservationService.resourceLocks` | `multinode`: 3 pods = 3 winners |
| D4 | Card hold and event run inside the transaction before COMMIT; rollback/retry leaves orphans. | `createInTransaction` | `partial`, `timeout` |
| D5 | Unbounded retry: 5 retries, fixed 10 ms backoff, random id per attempt, exception leaks as 500. | `RetryPolicy`, `reservation.properties` | `timeout`: 6 attempts, HTTP 500 |

Hard constraints (`Constraints.java`): 3+ pods; reject any partial overlap, including against rows that do
not exist yet; `lock_timeout = 50ms`; max 2 retries and retry amplification <= 1.10x; 20x burst on hot slots.

JVM-local locks are excluded by the multi-pod constraint, and a plain version counter on reservation rows is
excluded by the partial-overlap constraint (the rival row does not exist when we read). Two DB-backed
candidates were built from the platform reference patch and measured with the same harness.

## Options measured (`./scripts/benchmark.sh pessimistic fenced`, 3 pods, 3 burst rounds each)

| Candidate | Double bookings | Lock-timeout aborts | Max lock wait | Max attempts | Amplification | Cold-slot max | Verdict |
|---|---|---|---|---|---|---|---|
| `pessimistic`: `SELECT ... FOR UPDATE` on the parent resource row | 0 | **~90 per round (0.68 per request)** | 50.4 ms | 2 | **1.68-1.71x** | ~65 ms | rejected |
| `fenced`: slot-bucket locks + version-guarded claim | 0 | 0 | ~20-40 ms | 1 | 1.00x | ~5-7 ms | **accepted** |

* **pessimistic** is correct (the parent row always exists, and it lives in the shared DB), but every booking
  of a court queues on one row, including cold slots nobody competes for. At 20x, about 90 requests per round
  wait the full 50 ms, get `55P03`, and retry: 1.7x amplification against a 1.10x budget. With
  `reservation.locking.strategy=pessimistic`, `BurstContentionTest.lockWaitAndRetryBudget` fails for the same reason.
* **fenced** locks only the 15-minute buckets the interval touches. Overlapping intervals always share a bucket,
  so they serialize; everything else runs in parallel, and no wait reaches the 50 ms budget.

## Decision

`reservation.locking.strategy=fenced` (`SlotBucketFence`), plus:

1. Half-open overlap predicate (`TimeInterval.overlaps`) in `SlotAvailabilityChecker` (D1).
2. `slot_bucket(resource_id, bucket_start, version, occupants)`; buckets upserted and locked in ascending order,
   stop at the first bucket that already holds an overlapping booking; authoritative overlap check on the locked
   rows; claim = `UPDATE ... WHERE version = :seen` (fencing token) (D2, D3).
3. Reservation PK = `uuid(customer | Idempotency-Key)`, so replays on any pod return the original booking.
4. Card hold and created event go through the transactional outbox (RES-318) (D4).
5. `RetryPolicy`: retry only `55P03` / transient errors, max 2 retries (hard cap in code), full-jitter backoff
   (2-20 ms), 503 + Retry-After when the budget is spent (D5).
6. JVM-local lock and the `resource.booking_count` UPDATE removed.

`PessimisticResourceLock` stays behind the same `LockingStrategy` interface as a measured fallback.

## Consequences

* `./scripts/test.sh`: 27 passed, 0 failed. Benchmark: `fenced` accepted on every run.
* Bucket size trade-off: smaller buckets mean more locks per booking; larger ones serialize non-overlapping
  neighbours. 15 min = booking granularity (max 32 buckets for an 8 h booking).
* Ops: nightly cleanup of buckets older than 7 days; backfill buckets from confirmed reservations before rollout.
* Downstream systems see a booking up to one outbox relay interval (100 ms) after COMMIT.

# ADR-007: Slot-bucket fencing for the reservation write path

* Status: **Accepted**
* Context: [INC-2291](../INCIDENT-2291.md)
* Evidence: `./scripts/test.sh` (before/after), `./scripts/evaluate-designs.sh`

## Context

The create path had five defects, all reproduced by the test suites:

| # | Defect | Where | Proven by |
|---|---|---|---|
| D1 | Overlap predicate only checks "starts during an existing booking". It misses left-partial and enclosing overlaps. | `SlotAvailabilityChecker.findConflict` (re-implements `TimeInterval.overlaps` wrongly) | `overlap` suite: left-partial and enclosing get 201 |
| D2 | Check-then-insert race: the availability check is a plain READ COMMITTED read; nothing stops two transactions both passing it. | `ReservationService.createInTransaction` | `overlap`, `burst`, `multinode`: 3 winners for one slot |
| D3 | The RES-212 "fix" is a JVM-local `ReentrantLock`. It serializes one pod, not the cluster. | `ReservationService.resourceLocks` | `multinode`: 1 pod = 1 winner, 3 pods = 3 winners |
| D4 | Side effects (card hold, event) run *inside* the transaction before COMMIT. Rollback/retry leaves orphans and duplicates. | `paymentGateway.placeHold`, `eventBus.publish` in `createInTransaction` | `partial`, `timeout`: 6 orphaned holds |
| D5 | Unbounded retry: 5 retries, fixed 10ms backoff, random reservation id per attempt, exception leaks as 500. | `RetryPolicy`, `createReservation` | `timeout`: 6 attempts, 432ms, HTTP 500 |

## Constraints

(a) 3+ pods. (b) Overlap of **any part** of an interval, including against rows that do not exist yet.
(c) `lock_timeout = 50ms`, max 2 retries, amplification <= 1.10x, at 20x burst on hot slots.
Also: no orphaned / duplicated side effects.

## Options evaluated (same burst, 3 rounds, `evaluate-designs.sh`)

| Design | Double bookings | Lock timeouts | Max wait | Amplification | Cold-slot max | Verdict |
|---|---|---|---|---|---|---|
| A: JVM-local lock, 1 pod (control) | 0 | 0 | n/a | 1.00x | ~277 ms | fine only on one pod |
| A: JVM-local lock, 3 pods | **5** | 0 | n/a | 1.00x | ~104 ms | rejected by (a) |
| B: optimistic version + UNIQUE(resource, start) | **12** | 0 | 10.8 ms | 1.00x | 5 ms | rejected by (b) |
| C1: parent `SELECT ... FOR UPDATE` on resource | 0 | **268** | 51.2 ms | **1.69x** | 67 ms | rejected by (c) |
| C2: slot-bucket fencing + version guard | 0 | 0 | 21.0 ms | 1.00x | 5 ms | **accepted** |

* **A** cannot work: the lock lives in one JVM, and the other pods never see it.
* **B** cannot work: a version column protects rows that exist. A partially overlapping rival is a new
  row with a different start, so there is nothing to compare and nothing unique to collide on.
* **C1** is correct but too coarse. Every booking of a court queues on one row, including cold slots
  nobody competes for. At 20x, about 90 requests per round exceed 50ms and must retry (1.7x amplification).
* **C2** locks only what the interval touches. Overlapping intervals always share a bucket, so they
  serialize; everything else runs in parallel.

## Decision

1. **Fix the predicate** (D1): use the half-open `TimeInterval.overlaps`.
2. **Slot-bucket fence** (D2, D3, constraint b): new table `slot_bucket(resource_id, bucket_start, version, occupants)`,
   15-minute buckets. A booking upserts and locks the buckets its interval touches, in ascending order
   (deadlock-free, one round trip). It stops at the first bucket that already holds an overlapping
   booking. The authoritative overlap check runs on the locked rows.
3. **Version guard / fencing token**: the claim is `UPDATE slot_bucket ... WHERE (pk, version) IN (...)`.
   A moved token aborts the transaction (`40001`, retryable) instead of overwriting.
4. **Conditional insert / idempotency**: reservation PK = `uuid(customer | Idempotency-Key)`. A replay
   on any pod, including after a lost COMMIT ack, returns the original booking (200), never a 409 or a
   second row.
5. **Transactional outbox for create** (D4): `PLACE_PAYMENT_HOLD` and `RESERVATION_CREATED_EVENT`
   are written in the same transaction and delivered by `OutboxRelay` after COMMIT, with the
   reservation id as idempotency key.
6. **Bounded retry** (D5): retry only `55P03` / transient errors, at most 2 retries (hard cap in code),
   exponential backoff with full jitter (base 2ms, cap 20ms). When the budget is spent, return
   **503 + Retry-After** instead of a 500.
7. Remove the JVM-local lock and the `resource.booking_count` update. That hot parent-row UPDATE was
   an accidental C1 inside every booking; the dashboard now derives the count from events.

## Consequences

* After: 27/27 tests green. The 20x burst gives 0 double bookings, 0 orphaned side effects, max lock
  wait of about 20 ms, 1.00x amplification, and p99 of 23 ms (baseline p99: 114 ms). A stalled
  holder gives the waiter a 503 after exactly 3 attempts in about 160 ms (baseline: 6 attempts,
  432 ms, HTTP 500, 6 orphaned holds).
* The cancel path must also free its buckets (`SlotBucketFence.release`). The regression suite
  catches it if it doesn't.
* Bucket size trade-off: smaller buckets mean more locks per booking; larger buckets mean
  non-overlapping neighbours serialize. 15 min matches booking granularity; max 32 buckets for an 8h booking.
* Ops: `slot_bucket` grows with the calendar. Add a nightly job deleting buckets older than 7 days,
  and backfill buckets from existing confirmed reservations before enabling (migration script).
* Bookings now become visible to downstream systems up to one relay interval (100ms) after COMMIT.

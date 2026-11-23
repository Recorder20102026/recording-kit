# INC-2291: Double bookings and orphaned card holds during hot-slot release

| | |
|---|---|
| Severity | SEV-2 (customer money + trust) |
| Service | reservation-service (3 pods, HPA min=3) |
| Status | Mitigated (manual refunds), **root cause open** |

## What customers saw

* Two (sometimes three) customers received a confirmation for the **same court at overlapping times**
  during the Saturday 09:00 release of Centre Court slots. Several pairs only *partially* overlapped
  (e.g. 09:30-10:30 and 10:00-11:00).
* Customers whose booking "failed" (HTTP 500) still had a **pending card hold** for days. A few got a
  confirmation e-mail for a booking that does not exist.
* One customer retried after a timeout and was told the slot was taken (409) by... their own booking.

## What we saw

* The primary hit 100% of connections on `resource` row locks. Lock waits piled up behind slow
  transactions and the API retried them 5 times each. Classic retry storm.
* RES-212 (JVM-local per-resource lock) was hot-fixed last quarter "to stop double bookings". It
  passed in staging, **which runs a single pod**.

## Constraints from the post-incident review (non-negotiable)

| # | Constraint | Owner | Encoded in |
|---|---|---|---|
| C-1 | Service is horizontally scaled (3+ pods, autoscaling). Correctness may not depend on which pod serves a request. | Platform | `Constraints.NODES` |
| C-2 | Bookings are intervals. A new booking must be rejected if it overlaps **any part** of an existing one, including rows that do not exist yet at read time. | Product | `OverlappingIntervalTest` |
| C-3 | `lock_timeout = 50ms` on every connection. No request may wait longer for a row lock. | DBA | `db.lock.wait.timeout.ms`, `Constraints.LOCK_WAIT_BUDGET` |
| C-4 | Max **2 retries** per request; retry amplification (DB attempts / requests) <= 1.10 under burst. | SRE | `Constraints.MAX_RETRIES`, `MAX_RETRY_AMPLIFICATION` |
| C-5 | Hot slots see **20x** concurrent demand at release time. | Capacity planning | `Constraints.BURST_FACTOR` |
| C-6 | No card hold or event may exist for a reservation that was not committed, and none may be duplicated. | Payments | `Invariants.auditSideEffects` |

## Action items

* [ ] Root-cause the double bookings on the reservation path (owner: reservations team)
* [ ] Benchmark two candidate locking strategies, pessimistic vs optimistic/fenced, under C-1 .. C-5 and
      implement the one that satisfies C-1 .. C-6. The platform team's reference patch (GitHub repo
      `recording-kit`, folder `reference/`) has reusable locking blocks. Record the decision as an ADR.
* [ ] Make the create path stop leaking side effects on rollback / retry (RES-318 was only done for cancel)
* [ ] Prove it with the test suites in this repo before re-enabling the Saturday release

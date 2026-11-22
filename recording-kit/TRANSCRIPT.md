# Recording transcript: reservation-service double-booking investigation (IntelliJ IDEA)

Each step has three parts:

* **DO**: what to click or type (macOS IntelliJ shortcuts)
* **SAY**: the narration, written to be read aloud word for word. Every SAY line names the exact file,
  class, method, test or number on screen, so you never have to point at "this" or "that".
* **EXPECT**: what appears on screen. Numbers vary slightly from run to run; the pass/fail pattern does not.

Reference outputs from a real run are in `expected-output/`. Total running time is about 30–40 minutes.

---

## PHASE 1: Extract, open, capture the baseline (≈ 6 min)

### 1.1 Extract the archive
**DO**: In Finder, move `input 1.zip` to an empty working folder (e.g. `~/recording/`) and double-click `input 1.zip`.
Or open a terminal and run:
```bash
cd ~/recording && unzip "input 1.zip"
```
**SAY**: "I've been handed a brownfield reservation service as the archive `input 1.zip`. The open incident
is INCIDENT-2291: double bookings and orphaned card holds during a hot-slot release. I'll extract
`input 1.zip` and open the `input 1` folder in IntelliJ IDEA."

### 1.2 Open in IntelliJ IDEA
**DO**: IntelliJ → *File ▸ Open…* → select the extracted `input 1` folder → *Trust Project*.
Check *File ▸ Project Structure ▸ Project* shows SDK **25**. Expand `src/main/java` and `src/test/java` in the Project tool window.

**SAY**: "The `input 1` project is a plain Java project with no Maven or Gradle. The project compiles with
`javac` through `scripts/build.sh`. The main code is under the package `com.acme.reservations`. The
packages are: `api` for the HTTP layer, `service` for `ReservationService`, `persistence` for the
repositories, `outbox` for the transactional outbox, `sideeffects` for the payment gateway and the event
bus, and `db`, which models our shared PostgreSQL primary, including row locks and `lock_timeout`. The
`db` package lets the test suites measure real lock waits."

### 1.3 Read the incident and the constraints
**DO**: Open `docs/INCIDENT-2291.md` (⇧⌘O → `INCIDENT`). Scroll to the *Constraints* table.
Then open `src/test/java/.../testkit/Constraints.java`.

**SAY**: "The post-incident review in `INCIDENT-2291.md` gave us six hard constraints. Constraint one: we
run at least three pods, so correctness can't depend on which pod serves a request. Constraint two:
bookings are time intervals, and any partial overlap must be rejected. Constraint three: the DBA set
`lock_timeout` to 50 milliseconds. Constraint four: SRE allows at most two retries, with retry
amplification no higher than 1.1x. Constraint five: hot slots see 20x concurrent demand. Constraint six,
from the payments team: no orphaned or duplicate card holds. The six constraints live in
`Constraints.java` as `NODES = 3`, `LOCK_WAIT_BUDGET = 50 ms`, `MAX_RETRIES = 2`,
`MAX_RETRY_AMPLIFICATION = 1.10` and `BURST_FACTOR = 20`. `Constraints.java` is deliberately separate
from `reservation.properties`. `reservation.properties` is what I'm allowed to change; `Constraints.java`
is what I'm measured against."

### 1.4 Look at the current config
**DO**: Open `config/reservation.properties`.

**SAY**: "In `reservation.properties`, note `reservation.local.lock.enabled=true`. The property
`reservation.local.lock.enabled` is the RES-212 hotfix. Also note `retry.max.retries=5` with a fixed
10 millisecond backoff. Five retries already breaks the two-retry rule from `Constraints.java`."

### 1.5 Run the baseline suite in the IntelliJ terminal
**DO**: Open the terminal (⌥F12) and run:
```bash
./scripts/test.sh | tee reports/baseline-tests.txt
```
**SAY**: "`scripts/test.sh` builds the project and runs all six test suites: `OverlappingIntervalTest`,
`BurstContentionTest` for the 20x burst, `MultiNodeConsistencyTest`, `PartialFailureTest`,
`LockTimeoutRetryTest`, and `RegressionTest`. I'm piping the output through `tee` into
`reports/baseline-tests.txt`, so the baseline evidence stays with the repo."

**EXPECT**: `RESULT: 17 passed, 10 failed`. Scroll back through the output and narrate as you go.

### 1.6 Walk through the failure modes (scroll the terminal)
**SAY**, while scrolling:
* **`OverlappingIntervalTest`**: "`identicalIntervalRejected`, `containedIntervalRejected` and
  `rightPartialOverlapRejected` pass. But `leftPartialOverlapRejected`, 09:30 to 10:30 against an existing
  10:00 to 11:00 booking, and `enclosingIntervalRejected` both get HTTP 201 instead of 409. Those two
  failures are an overlap-predicate bug, and the predicate bug fails even single-threaded.
  `concurrentPartialOverlapsAcrossNodes` fails 10 out of 10 rounds."
* **`BurstContentionTest` report**: "The burst sends 130 requests over three pods, 20 requests per hot
  slot. The report shows 5 double bookings. The conflict rate is about 87%, which is expected, because
  most requests for a hot slot must lose. But look at latency: p50 is around 67 milliseconds and p99 is
  around 114 milliseconds, while the database lock waits are close to zero. The 114 milliseconds is
  spent queueing on something other than the database."
* **`MultiNodeConsistencyTest`**: "`MultiNodeConsistencyTest` is the key evidence. In
  `singleNodeControl`, 21 identical requests on **one** pod give exactly one winner, three rounds in a
  row. In `sameSlotAcrossThreeNodes`, the same 21 requests spread over **three** pods give three winners,
  one winner per pod. In `idempotentRetryAcrossNodes`, a client retry of the same request on another pod
  creates a second reservation."
* **`PartialFailureTest`**: "In `commitFailureLeavesNoOrphans`, a COMMIT that fails once leaves an
  orphaned card hold and an orphaned 'reservation created' event. In `commitAckLostIsIdempotent`, the
  COMMIT succeeds but the acknowledgement is lost, and the client retry gets HTTP 409, slot taken, by the
  customer's own booking."
* **`LockTimeoutRetryTest`**: "In `stalledHolderBoundedWaiter`, a lock holder stalls. The waiting request
  tries six times, takes about 430 milliseconds, returns a raw HTTP 500, and leaves six card holds behind.
  `stalledHolderBoundedWaiter` shows retry amplification and partial state together."
* **`RegressionTest`**: "`RegressionTest` is all green. Every change I make must keep `RegressionTest`
  green."

**SAY**: "So the baseline has four failure classes: a wrong overlap predicate, a check-then-insert race
across pods, side effects that escape the database transaction, and an unbounded retry loop."

---

## PHASE 2: Trace the reservation path (≈ 8 min)

### 2.1 Call Hierarchy: who calls the write path
**DO**: ⌘O → `ReservationService` → put the caret on `createReservation` → **⌃⌥H** (Call Hierarchy).
Expand the callers.

**SAY**: "The Call Hierarchy for `ReservationService.createReservation` shows the callers:
`ReservationController.postReservation`, which is reached from `Cluster.post` through the round-robin
load balancer, plus the test suites. Every pod, meaning every `ServiceNode`, has its own
`ReservationService` instance. One `ReservationService` per pod matters in a moment."

### 2.2 Callee view: what happens inside the transaction
**DO**: In the Call Hierarchy window click **Callee Hierarchy** (the button with the arrow pointing down). Select
`createInTransaction` and expand it. Then open `createInTransaction` in the editor.

**SAY**: "Inside one `db.inTransaction` call, `createInTransaction` does seven things in order:
1. read the resource row,
2. call `availability.findConflict`, a plain non-locking read,
3. call `paymentGateway.placeHold`, an external HTTP call to the payment provider,
4. insert the reservation row,
5. call `resources.incrementBookingCount`, an UPDATE on the parent resource row,
6. call `eventBus.publish`,
7. and only after the lambda returns does `Database.inTransaction` run COMMIT."

**DO**: ⌘-click `inTransaction` to show `Database.inTransaction`: work runs first, `tx.commit()` runs last.

**SAY**: "In `Database.inTransaction`, the work runs first and `tx.commit()` runs last. So
`paymentGateway.placeHold` and `eventBus.publish` happen **before** COMMIT. If the COMMIT fails or the
lock wait times out, the rollback removes the reservation row but cannot undo the card hold at the
payment provider or the published event. The card hold and the event that survive the rollback are the
orphans from `PartialFailureTest`."

### 2.3 Find Usages: the overlap predicate
**DO**: Open `SlotAvailabilityChecker.findConflict`. Read the inline condition. Then ⌘O → `TimeInterval` →
caret on `overlaps` → **⌥F7** (Find Usages).

**SAY**: "`SlotAvailabilityChecker.findConflict` only asks whether the new booking **starts** while an
existing booking is running. The start-only check misses a booking that starts earlier and runs into an
existing booking, and misses a booking that encloses an existing booking. The correct half-open predicate
already exists in `TimeInterval.overlaps`. Find Usages on `TimeInterval.overlaps` shows that production
code never calls `TimeInterval.overlaps`; only test code does. The start-only check in `findConflict` is
bug number one."

### 2.4 Find Usages: the JVM-local lock
**DO**: In `ReservationService`, caret on the field `resourceLocks` → ⌥F7.

**SAY**: "The field `resourceLocks` is the RES-212 hotfix: a `ConcurrentHashMap` of `ReentrantLock`
objects, one lock per resource. `resourceLocks` is a field of `ReservationService`, and the Call Hierarchy
just showed one `ReservationService` instance per pod. So `resourceLocks` only serializes requests inside
a single JVM. Staging runs one pod, which is why RES-212 looked fixed in staging. `resourceLocks` also
explains the burst latency in `BurstContentionTest`: each pod queues all bookings for a court behind one
`ReentrantLock` that is held across the `paymentGateway.placeHold` call."

### 2.5 Find Usages: the hidden parent-row lock
**DO**: Caret on `incrementBookingCount` → ⌥F7, then ⌘B into `ResourceRepository.incrementBookingCount`.

**SAY**: "Every booking also runs `ResourceRepository.incrementBookingCount`, an UPDATE on the parent
resource row for a dashboard counter. Under PostgreSQL, an UPDATE takes a row lock held until COMMIT, so
the baseline is accidentally doing parent-row serialization already. And because
`incrementBookingCount` runs **after** `findConflict`, the parent-row lock serializes bookings without
protecting the availability check. The parent-row lock from `incrementBookingCount` is also the lock
that `stalledHolderBoundedWaiter` times out on."

### 2.6 Find Usages: the outbox that create never used
**DO**: ⌘O → `OutboxMessage` → caret on `PLACE_PAYMENT_HOLD` → ⌥F7. Then open `OutboxRelay.dispatch` and
`ReservationService.cancelReservation`.

**SAY**: "The transactional outbox already exists. `OutboxMessage` and `OutboxRelay` came in with
RES-318, and `ReservationService.cancelReservation` already uses the outbox. `OutboxRelay.dispatch`
even knows how to place a payment hold, but in production code nothing ever enqueues
`PLACE_PAYMENT_HOLD`. The only other Find Usages hit for `PLACE_PAYMENT_HOLD` is under Test sources, in
the evaluation spike `SpikeSupport`. The create path was never migrated to the outbox. Moving
`placeHold` and `publish` into the outbox is my fix for partial state, and the fix reuses the existing
RES-318 infrastructure."

### 2.7 Debug: watch the race happen
**DO**:
1. In `ReservationService.createInTransaction`, click the gutter on the line
   `String reservationId = "R-" + UUID.randomUUID();` (the first line **after** the availability check) to add a breakpoint.
2. Right-click the red dot → set **Suspend: Thread** (not *All*) → *Done*.
3. ⌘O → `RaceReproMain` → click the green gutter arrow next to `main` → **Debug 'RaceReproMain.main()'**.

**EXPECT**: The Debug tool window shows **two** suspended threads (ForkJoinPool workers) at the same line.
Use the thread dropdown in *Frames* to switch between the two threads. In *Variables*, expand `request` to see
interval `10:00–11:00` on one thread and `10:30–11:30` on the other. `clash` is `Optional.empty` in both.

**SAY**: "`RaceReproMain` sends two customers to two pods with partially overlapping intervals: 10:00 to
11:00 and 10:30 to 11:30. Both threads are parked on the line right after `findConflict`, and in both
threads the variable `clash` is `Optional.empty`, so both requests saw no conflict. Nothing has been
written to the database yet. From the breakpoint onward, both requests will book. The two threads at the
same breakpoint are the check-then-act race, live."

**DO**: Resume (⌥⌘R) until the program finishes. Show the console.

**EXPECT**: `>>> DOUBLE BOOKING reproduced`, two CONFIRMED rows, two card holds.

**SAY**: "The console prints `DOUBLE BOOKING reproduced`, with two CONFIRMED reservations and two card
holds for overlapping intervals on the same court."

**DO**: Remove the breakpoint (⌘F8 on the line). Optional: also run `./scripts/repro-race.sh | tee reports/baseline-race-repro.txt`.

---

## PHASE 3: Evaluate three locking designs against the constraints (≈ 7 min)

### 3.1 Frame the decision
**SAY**: "I have three locking design families, and each design family runs into one hard constraint.
Design (a) is JVM-local locks, which is what the baseline has. Multi-instance deployment should rule out
JVM-local locks.
Design (b) is row-level optimistic versioning. Partial overlap against rows that don't exist yet should
rule out optimistic versioning.
Design (c) is real database locks. For database locks, the 50 millisecond lock timeout and the two-retry
budget at 20x burst force a choice between pessimistic serialization on the parent resource row, called
C1, and a scoped key fence on time buckets plus a version guard, called C2. I'll measure all four
designs instead of arguing about them."

### 3.2 Show the spikes
**DO**: Open `src/test/java/.../evaluation/spikes/`. Briefly show each class:
* `SpikeSupport`: "`SpikeSupport` gives every spike the same idempotency lookup, the same correct overlap
  predicate, the same writes and the same bounded retry. Only the locking differs between spikes, so the
  comparison is fair."
* `JvmLocalLockDesign` (A): "`JvmLocalLockDesign` is design A: a per-pod `ReentrantLock` with the correct
  overlap predicate."
* `OptimisticVersionDesign` (B): "`OptimisticVersionDesign` is design B: a version check on the rows I
  read, plus a `UNIQUE(resource, start)` constraint. The comment in `OptimisticVersionDesign` states the
  weakness: the rival reservation row doesn't exist when I read, so there's no version to compare."
* `ParentResourceLockDesign` (C1): "`ParentResourceLockDesign` is design C1: `SELECT … FOR UPDATE` on the
  resource row, then re-check the overlap."
* `SlotBucketFenceDesign` (C2): "`SlotBucketFenceDesign` is design C2: lock only the 15-minute bucket rows
  that the requested interval touches. The bucket rows are upserted, so a bucket row exists even when the
  rival booking doesn't. C2 locks the bucket rows in sorted order, stops at the first bucket that already
  holds an overlapping booking, checks overlap on the locked bucket rows, and then claims the buckets with
  a version-guarded UPDATE."

### 3.3 Run the evaluation
**DO**:
```bash
./scripts/evaluate-designs.sh | tee reports/design-evaluation.txt
```
**SAY**: "`scripts/evaluate-designs.sh` runs every design through three rounds of the same 20x burst on
three pods, and saves the output to `reports/design-evaluation.txt`."

**EXPECT** (≈ 15 s): per-round lines, then the summary table. See `expected-output/3-design-evaluation.txt`.

### 3.4 Read the table, one constraint at a time
**SAY**:
* "**Design A on one pod**: zero double bookings. Design A on one pod is the control, and the control
  explains why staging was green. But the cold-slot latency for design A on one pod is around 270
  milliseconds, because the whole court is serialized in one JVM."
* "**Design A on three pods**: five double bookings. Constraint (a), multi-instance deployment, rules out
  JVM-local locks."
* "**Design B**: about twelve double bookings, and all twelve are partial overlaps. Identical intervals
  collide on the `UNIQUE(resource, start)` key, but a 09:30 booking against a 10:00 booking is a brand-new
  row with nothing to version-check. Constraint (b), partial overlap, rules out optimistic versioning."
* "**Design C1**: zero double bookings, so C1 is correct. But about 90 requests per round hit the 50
  millisecond `lock_timeout`, retry amplification is 1.7x against a 1.1x limit, and even the cold slots
  that nobody competes for take about 67 milliseconds, because every booking for the court queues on one
  resource row. Constraint (c), the 50 millisecond and two-retry budget, rules out parent-row locking.
  And parent-row locking is what the baseline was half-doing with the `incrementBookingCount` UPDATE."
* "**Design C2**: zero double bookings, zero lock timeouts, a maximum lock wait around 20 milliseconds
  against the 50 millisecond budget, 1.0x amplification, and cold slots in about 5 milliseconds. Slot-bucket
  fencing is the only design that meets all three constraints."

### 3.5 State the decision
**SAY**: "Decision: slot-bucket fencing with a version guard, design C2. On top of the fence, I'll fix the
overlap predicate in `SlotAvailabilityChecker`, use a deterministic reservation id so retries are
idempotent, move the card hold and the event to the transactional outbox, and enforce a two-retry budget
with jitter that returns HTTP 503 instead of HTTP 500. I'll record the decision as ADR-007."

---

## PHASE 4: Implement the fix, then verify (≈ 12–15 min)

> The final versions of every file are in `recording-kit/solution/` (same paths as the project).
> Type the small edits live and paste the larger ones. Keep `recording-kit/solution/` open in a second window or in
> Finder. `solution.patch` applies everything at once if you need to recover:
> `patch -p1 < ../recording-kit/solution.patch`, run from the project root.

### 4.1 Fix the overlap predicate (type it)
**DO**: In `SlotAvailabilityChecker.findConflict`, replace the loop body with:
```java
            // Half-open overlap: catches left/right partial, contained, enclosing and identical intervals.
            if (existing.interval().overlaps(requested)) {
                return Optional.of(existing);
            }
```
Then press ⌃⌥O to remove the unused `Instant` import. Run:
```bash
./scripts/test.sh overlap
```
**SAY** (while typing): "I'm replacing the start-only check in `findConflict` with a call to
`TimeInterval.overlaps`, the half-open predicate that already existed."

**EXPECT**: 6 passed, 1 failed. Only `concurrentPartialOverlapsAcrossNodes` still fails.

**SAY**: "`OverlappingIntervalTest` now gives 6 passed, 1 failed. The sequential cases, including
`leftPartialOverlapRejected` and `enclosingIntervalRejected`, are fixed. `concurrentPartialOverlapsAcrossNodes`
still fails, which proves the overlap predicate was only half the problem. The other half is the
check-then-insert race across pods."

### 4.2 Add the bucket table
**DO**: Right-click `persistence` → *New ▸ Java Class* → `SlotBucket` → paste from
`solution/.../persistence/SlotBucket.java`.
In `Schema.java`, add:
```java
    public static final TableDef<SlotBucket> SLOT_BUCKETS = new TableDef<>("slot_bucket");
```
and add the `slot_bucket` line to the DDL comment.

**SAY**: "The new `slot_bucket` table has one row per court per 15-minute bucket. Each `SlotBucket` row
holds a version, which is the fencing token, and the list of occupants. The `slot_bucket` table gives me
a row to lock even when the competing reservation doesn't exist yet."

### 4.3 Add the fence
**DO**: Right-click `service` → *New ▸ Java Class* → `SlotBucketFence` → paste.
Walk through `bucketKeys`, `acquire`, `Fence.clash`, `Fence.claim`, `release`.

**SAY**: "`SlotBucketFence.bucketKeys` returns the sorted bucket keys that an interval touches. Any two
overlapping intervals share at least one bucket key. `SlotBucketFence.acquire` upserts and locks the
bucket rows in one round trip, in ascending key order so there are no deadlocks, and `acquire` stops at
the first bucket that already contains an overlapping booking. A request that is going to lose therefore
releases its locks immediately instead of queueing for the remaining buckets. `Fence.clash` returns the
overlapping occupant, if there is one. `Fence.claim` is the version guard: an UPDATE with
`WHERE version = :seen` on every bucket, and if any bucket's version moved, the transaction aborts.
`SlotBucketFence.release` removes the reservation from its buckets on cancellation."

### 4.4 Config
**DO**: Edit `config/reservation.properties` (or paste from `solution/config/`):
* delete the `reservation.local.lock.enabled` block
* `retry.max.retries=2`
* replace `retry.backoff.ms` with `retry.backoff.base.ms=2` and `retry.backoff.max.ms=20`
* add `retry.client.retry-after.ms=200` and `slot.bucket.minutes=15`

Then replace `ReservationConfig.java` with the solution version.

**SAY**: "In `reservation.properties`, I delete `reservation.local.lock.enabled`, so the RES-212 JVM-local
lock is gone. `retry.max.retries` goes from 5 to 2. `retry.backoff.base.ms=2` and
`retry.backoff.max.ms=20` give exponential backoff with full jitter. `retry.client.retry-after.ms=200` is
the Retry-After value for the HTTP 503. `slot.bucket.minutes=15` is the bucket size. I picked 15 minutes
because 15 minutes is our booking granularity. Smaller buckets mean more locks per booking; larger buckets
serialize neighbouring bookings that don't actually overlap. `ReservationConfig.java` reads the new
properties."

### 4.5 Bounded retry
**DO**: Replace `RetryPolicy.java` with the solution version. Point at `HARD_MAX_RETRIES`, the catch clause,
the full-jitter `backoff`, and `onExhausted`.

**SAY**: "The new `RetryPolicy` retries only lock timeouts and transient database errors, never an HTTP
409 conflict. `HARD_MAX_RETRIES` caps retries at two in code, even if someone raises
`retry.max.retries` in the config. The full-jitter `backoff` method stops competing requests from coming
back in lock-step. And when the retry budget runs out, `onExhausted` returns a value to the caller, an
HTTP 503 with Retry-After, instead of throwing an exception that becomes HTTP 500."

### 4.6 The reservation service
**DO**: Replace `ReservationService.java` with the solution version. Then scroll through `createInTransaction`
and read the numbered comments out loud.

**SAY**:
"Step 0 in `createInTransaction` is the idempotent replay. `reservationIdFor` derives the reservation id
from the customer id plus the Idempotency-Key, so a retry on any pod, or a retry after a lost COMMIT
acknowledgement, finds the original booking and returns the original booking.
Step 1 is a cheap, lock-free fast path through `SlotAvailabilityChecker.findConflict` that rejects
requests which already clash with a committed booking.
Step 2 is `fence.acquire`, which locks the slot buckets.
Step 3 is `fence.clash`, the authoritative overlap check on the bucket rows I'm holding locks on.
Step 4 is the conditional insert, where the primary key is the deterministic reservation id, plus
`fence.claim`, the version-guarded claim.
Step 5 enqueues the card hold and the 'reservation created' event into the outbox in the same transaction.
In the new `ReservationService`, the `resourceLocks` JVM lock is gone, the `paymentGateway.placeHold` and
`eventBus.publish` calls inside the transaction are gone, and the `incrementBookingCount` UPDATE on the
hot parent row is gone. `cancelReservation` now also calls `SlotBucketFence.release`."

### 4.7 Wire it
**DO**: In `cluster/ServiceNode.java`, change the constructor call to:
```java
        this.service = new ReservationService(name, db, reservations, resources, outbox,
                new SlotAvailabilityChecker(reservations), RetryPolicy.fromConfig(config), config, metrics);
```
Then build:
```bash
./scripts/build.sh
```
**SAY**: "In `ServiceNode`, the `ReservationService` constructor no longer receives the payment gateway
or the event bus, only the outbox. `scripts/build.sh` compiles the project."

**EXPECT**: `[build] OK (38 main / 24 test sources)`.

### 4.8 Verify the targeted suites first
**DO**:
```bash
./scripts/test.sh overlap multinode partial timeout
```
**SAY** as results scroll: "`OverlappingIntervalTest` passes every overlap shape, including
`concurrentPartialOverlapsAcrossNodes`. In `MultiNodeConsistencyTest`, three pods give exactly one winner,
and in `idempotentRetryAcrossNodes` a replay on another pod returns the same reservation id. In
`PartialFailureTest`, a failed COMMIT leaves zero orphaned holds and zero orphaned events, and a lost
COMMIT acknowledgement returns the original booking. In `LockTimeoutRetryTest`, the stalled holder makes
the waiting request time out at 50 milliseconds, try three times in total, and answer HTTP 503 with
Retry-After in about 160 milliseconds, with zero payment calls and zero orphans."

### 4.9 Burst contention
**DO**:
```bash
./scripts/test.sh burst
```
**SAY**: "`BurstContentionTest` runs the same 20x burst over three pods. Zero double bookings, every
HTTP 409 is justified by a real overlapping booking, and all ten cold slots are booked. The maximum lock
wait is around 20 milliseconds against the 50 millisecond budget, the maximum attempts per request is 1,
retry amplification is 1.0x, and there are zero orphaned side effects. p99 latency went from about 114
milliseconds in the baseline to about 23 milliseconds."

### 4.10 Full suite + evidence
**DO**:
```bash
./scripts/test.sh | tee reports/after-fix-tests.txt
./scripts/repro-race.sh | tee reports/after-fix-race-repro.txt
```
**EXPECT**: `RESULT: 27 passed, 0 failed`. The race repro shows one 201, one 409 and `>>> no double booking`.

**SAY**: "The full suite gives 27 passed, 0 failed, with `RegressionTest` still green. `repro-race.sh`
now ends with one HTTP 201, one HTTP 409, and `no double booking`."

### 4.11 (Optional, strong visual) Debug the fixed version
**DO**: Breakpoint (**Suspend: Thread**) on the line `Optional<SlotBucket.Occupant> clash = fence.clash();`
in `createInTransaction` → Debug `RaceReproMain`.

**EXPECT**: Only **one** thread stops at the breakpoint: the thread holding the bucket locks. The other thread is blocked in
`LockManager.acquire`. Wait a moment before resuming, and the blocked request gives up after 3×50 ms.

**SAY**: "I've frozen one pod mid-transaction with the debugger, on the `fence.clash()` line. A frozen pod
is the worst case: a stalled lock holder. The second request is blocked in `LockManager.acquire` and
can't get past the bucket fence. The second request waits 50 milliseconds, retries twice with jitter,
and returns HTTP 503. The second request never hangs and never double books. When I resume the frozen
thread, the first request commits."

### 4.12 Record the decision
**DO**: Create `docs/adr/ADR-007-reservation-locking.md` (paste from the solution and update the numbers to
match your `reports/`).

**SAY**: "`ADR-007-reservation-locking.md` lists the five defects with the file where each defect was
found, the four measured design options, the decision for slot-bucket fencing, and the consequences.
The consequences include a cleanup job for old `slot_bucket` rows, backfilling `slot_bucket` rows before
rollout, and the fact that events now go out up to one `OutboxRelay` interval after COMMIT."

### 4.13 Show what changed
**DO**: Right-click the project root → *Local History ▸ Show History* (or open each changed file and use
*Compare with Clipboard / Local History*). Show `reports/` with the before/after evidence.

**SAY**: "The full change: the overlap-predicate fix in `SlotAvailabilityChecker`, two new classes
`SlotBucket` and `SlotBucketFence` for the bucket fence, the bounded `RetryPolicy`, the rewritten create
path in `ReservationService`, the changes to `reservation.properties` and `ReservationConfig`, the
`slot_bucket` table in `Schema`, the constructor wiring in `ServiceNode`, and ADR-007. The `reports/`
folder holds the baseline and after-fix evidence."

---

## Closing (≈ 30 s)
**SAY**: "To sum up, the baseline had four root causes: a wrong overlap predicate in
`SlotAvailabilityChecker`, a check-then-insert race that the JVM-local `resourceLocks` can't stop across
pods, the card hold and event escaping the database transaction, and an unbounded retry loop. I measured
the locking designs against the real constraints. JVM-local locks fail multi-instance deployment,
optimistic versioning fails partial overlap, and parent-row locking fails the 50 millisecond and
two-retry budget at 20x burst. Scoped slot-bucket fencing with a version guard, a transactional outbox
and bounded jittered retries meets every constraint: zero double bookings, zero orphaned side effects,
lock waits well under 50 milliseconds, and never more than two retries."

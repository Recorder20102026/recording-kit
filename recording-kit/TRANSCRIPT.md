# Recording transcript: reservation-service locking investigation (IntelliJ IDEA + GitHub reference patch)

Each step has three parts:

* **DO**: what to click or type (macOS IntelliJ shortcuts)
* **SAY**: the narration, written to be read aloud word for word. Every SAY line names the exact file,
  class, method, test or number on screen, so you never have to point at "this" or "that".
* **EXPECT**: what appears on screen. Numbers vary slightly from run to run; the pass/fail pattern does not.

**Copy-paste vs. typed.** The large blocks come from the GitHub reference patch:
`https://github.com/Recorder20102026/recording-kit/tree/main/reference`. Open each file there in Chrome, click
the **Copy raw file** button (two-squares icon, top right of the file view), and paste into IntelliJ.
You type only the **five small edits** marked ✍️ (exact text is in this transcript):

| ✍️ | Phase | File | What |
|---|---|---|---|
| T1 | 3.1 | `SlotAvailabilityChecker.java` | overlap predicate, 4 lines |
| T2 | 3.4 | `ServiceNode.java` | integration: pick the `LockingStrategy` from config, 8 lines |
| T3 | 3.5 | `reservation.properties` | timeout / retry wiring, 7 lines |
| T4 | 3.6 | `LockingBenchmark.java` | constraint assertions in `checkConstraints`, 11 lines |
| T5 | 4.3 | `reservation.properties` | `pessimistic` → `fenced`, 1 word |

**Pasting a new class in IntelliJ:** copy the code, click the target package in the Project tool window
(for example `service`) and press ⌘V. IntelliJ creates the `.java` file with the right name. **Replacing an
existing file:** open it, ⌘A, ⌘V.

Reference outputs from a real run are in `recording-kit/expected-output/`. Total running time is about 35 minutes.

---

## PHASE 1: Extract, open, run the baseline, inspect the reference patch (≈ 8 min)

### 1.1 Extract the archive
**DO**: In Finder, move `input 1.zip` to an empty working folder (e.g. `~/recording/`) and double-click `input 1.zip`.

**SAY**: "I've been handed a brownfield reservation service as the archive `input 1.zip`. The open
incident is INCIDENT-2291: overlapping time-range double bookings during a hot-slot release, on a service
that runs on several pods. I'll extract `input 1.zip` and open the `input 1` folder in IntelliJ IDEA."

### 1.2 Open in IntelliJ IDEA
**DO**: IntelliJ → *File ▸ Open…* → select the extracted `input 1` folder → *Trust Project*.
Check *File ▸ Project Structure ▸ Project* shows SDK **25**. Expand `src/main/java` and `src/test/java`.

**SAY**: "The `input 1` project is plain Java with no Maven or Gradle; `scripts/build.sh` compiles the
project with `javac`. The main code is in `com.acme.reservations`: `service` holds `ReservationService`,
`persistence` holds the repositories, `outbox` is the transactional outbox, and `db` models our shared
PostgreSQL primary, including row locks and a real `lock_timeout`, so the tests measure real lock waits.
`src/test/java` has six test suites and a `testkit` package with a load generator and invariants."

### 1.3 Read the incident and the constraints
**DO**: ⇧⌘O → `INCIDENT-2291.md`, scroll to the *Constraints* table. Then ⌘O → `Constraints`.

**SAY**: "`INCIDENT-2291.md` lists the hard constraints. We run at least three pods. Any partial overlap
must be rejected, including against rows that don't exist yet when we read. The DBA set `lock_timeout` to
50 milliseconds. SRE allows at most two retries and a retry amplification of 1.1x. Hot slots see 20x
concurrent demand. `Constraints.java` holds the same rules as `NODES = 3`, `LOCK_WAIT_BUDGET = 50 ms`,
`MAX_RETRIES = 2`, `MAX_RETRY_AMPLIFICATION = 1.10` and `BURST_FACTOR = 20`. `Constraints.java` is what
I'm measured against; `config/reservation.properties` is what I'm allowed to change."

### 1.4 Look at the current config
**DO**: Open `config/reservation.properties`.

**SAY**: "In `reservation.properties`, `db.lock.wait.timeout.ms=50` is the DBA's lock timeout.
`reservation.local.lock.enabled=true` is the RES-212 hotfix, a lock inside the JVM. And
`retry.max.retries=5` with a fixed 10 millisecond backoff already breaks the two-retry rule."

### 1.5 Run the baseline suite in the IntelliJ terminal
**DO**: Open the terminal (⌥F12) and run:
```bash
./scripts/test.sh | tee reports/baseline-tests.txt
```
**SAY**: "`scripts/test.sh` builds the project and runs all six suites: `OverlappingIntervalTest`,
`BurstContentionTest`, `MultiNodeConsistencyTest`, `PartialFailureTest`, `LockTimeoutRetryTest` and
`RegressionTest`. `tee` saves the output to `reports/baseline-tests.txt`."

**EXPECT**: `RESULT: 17 passed, 10 failed`, then the list of 10 failures (`expected-output/1-baseline-tests.txt`).
The burst double-booking count varies (2–5); the set of failing tests does not.

### 1.6 Read the failure modes (scroll the failure list)
**SAY**, while scrolling:
* "`OverlappingIntervalTest.leftPartialOverlapRejected` and `enclosingIntervalRejected` get HTTP 201 instead
  of 409: 09:30 to 10:30 against an existing 10:00 to 11:00 booking is accepted. The overlap predicate is
  wrong even single-threaded. `concurrentPartialOverlapsAcrossNodes` double books in 10 out of 10 rounds."
* "`BurstContentionTest.noDoubleBookings` finds overlapping confirmed reservations after the 20x burst."
* "`MultiNodeConsistencyTest.sameSlotAcrossThreeNodes` gets three winners for one slot, one winner per pod.
  The RES-212 lock lives in each pod's JVM, so the three pods never see each other's lock."
* "`LockTimeoutRetryTest.stalledHolderBoundedWaiter` waits 50 milliseconds per attempt, tries six times,
  takes about 416 milliseconds and returns HTTP 500. Six attempts breaks the two-retry rule."
* "`PartialFailureTest` shows orphaned card holds, because the payment call runs before COMMIT."

### 1.7 Confirm the two root causes in the code
**DO**: ⌘O → `SlotAvailabilityChecker` → read `findConflict`. Then ⌘O → `ReservationService` → caret on the
field `resourceLocks` → **⌥F7** (Find Usages).

**SAY**: "`SlotAvailabilityChecker.findConflict` only asks whether the new booking **starts** during an
existing one, which misses left-partial and enclosing overlaps. `findConflict` is also a plain non-locking
read, so two pods can both pass the check before either one inserts. Find Usages on `resourceLocks` shows
a `ConcurrentHashMap` of `ReentrantLock` objects inside `ReservationService`, one `ReservationService` per
pod. A JVM-local lock cannot coordinate three pods; the lock has to live in the shared database."

### 1.8 Inspect the reference patch on GitHub
**DO**: Switch to Chrome → `https://github.com/Recorder20102026/recording-kit/tree/main/reference` → open
`README.md`, then click `reference.patch` and scroll through the file list.

**SAY**: "The platform team's reference patch has the larger locking structures I can reuse. The
`reference/README.md` table lists them: `LockingStrategy`, the interface; `PessimisticResourceLock`, a
`SELECT FOR UPDATE` on the parent resource row; `SlotBucketFence` with `SlotBucket`, which locks only the
time buckets an interval touches and claims them with a version guard; a bounded `RetryPolicy`; a
`ReservationService` driven by a `LockingStrategy`; and `LockingBenchmark`, a race and burst harness. The
README also says what is **not** in the patch: the integration in `ServiceNode`, the timeout and retry
values in `reservation.properties`, and the constraint assertions in `LockingBenchmark.checkConstraints`.
Those three parts are service-specific, so I'll write them myself."

**SAY**: "Both candidates keep their locks in the database, so both survive multiple pods. Neither one
relies on a version counter alone: a version counter on reservation rows can't protect a partial overlap,
because the rival row doesn't exist yet when I read. What separates the two candidates is lock granularity,
and lock granularity decides lock-wait timeouts and retry amplification. I'll measure that, not guess it."

---

## PHASE 2: Build the race / 20x burst harness (≈ 6 min)

### 2.1 Add `LockingBenchmark`
**DO**: Chrome → `reference/src/test/java/com/acme/reservations/testkit/LockingBenchmark.java` → **Copy raw file**.
IntelliJ → click the `testkit` package under `src/test/java` → ⌘V.

**SAY**: "`LockingBenchmark` goes into the existing `testkit` package, next to `LoadRunner`, `RunReport`
and `Workloads`, which `LockingBenchmark` reuses."

### 2.2 Add `scripts/benchmark.sh`
**DO**: Chrome → `reference/scripts/benchmark.sh` → **Copy raw file**. IntelliJ → right-click `scripts` →
*New ▸ File* → `benchmark.sh` → ⌘V. In the terminal:
```bash
chmod +x scripts/benchmark.sh
```

### 2.3 Walk through the harness
**DO**: In `LockingBenchmark`, show `RACE_ROUNDS`, `measure`, the `Result` record and `checkConstraints`.

**SAY**: "`LockingBenchmark.measure` runs two workloads on a fresh three-pod cluster. First, the overlap
race: `RACE_ROUNDS` = 10 rounds of three partially overlapping requests, 10:00 to 11:00, 10:30 to 11:30
and 09:30 to 10:30, fired at the same instant on three different pods. Second, the 20x burst from
`Workloads.hotSlotBurst`: one discarded JIT warm-up round, then three measured rounds. The `Result` record
collects the counters: double bookings, 409 conflicts, 503s and 500s, lock-wait timeouts, the maximum lock
wait, retries, attempts per request, retry amplification, p99 latency and cold-slot latency.
`checkConstraints` returns `null` for now; I'll write the assertions once the candidates exist.
For each strategy name passed to `benchmark.sh`, `LockingBenchmark` sets `reservation.locking.strategy`;
with no names, `LockingBenchmark` runs whatever `reservation.properties` configures."

### 2.4 Measure the baseline
**DO**:
```bash
./scripts/benchmark.sh | tee reports/baseline-benchmark.txt
```
**EXPECT** (`expected-output/2-baseline-benchmark.txt`): strategy `(current code)`; overlap race with 10 rounds
with a double booking; burst rounds with 4–7 double bookings each, lock timeouts 0, p99 ≈ 113–127 ms,
cold max ≈ 107–125 ms; verdict `n/a - no checks yet`.

**SAY**: "The harness reproduces INCIDENT-2291 on demand: 10 out of 10 race rounds double book, and every
burst round double books, dozens of overlapping pairs in total. The database lock timeouts are zero and the
maximum database lock wait is about 4 milliseconds, yet p99 latency is around 120 milliseconds and even cold
slots that nobody competes for take over 100 milliseconds. The time is spent queueing on the JVM-local
`ReentrantLock`, not in the database. That's the baseline the two candidates have to beat."

---

## PHASE 3: Implement both candidate locking designs (≈ 12 min)

### 3.1 ✍️ T1: fix the overlap predicate (shared by both candidates)
**DO**: In `SlotAvailabilityChecker.findConflict`, replace the body of the `for` loop with:
```java
            // Half-open overlap: catches left/right partial, contained, enclosing and identical intervals.
            if (existing.interval().overlaps(requested)) {
                return Optional.of(existing);
            }
```
Optional: ⌃⌥O removes the now-unused `import java.time.Instant;`.

**SAY**: "Both candidates re-check overlap, so both need a correct predicate. `TimeInterval.overlaps` is
the half-open test, start before the other's end and end after the other's start, and it already exists."

### 3.2 Paste the candidate structures from the reference patch
**DO**: For each file: Chrome → `reference/src/main/java/com/acme/reservations/...` → **Copy raw file** → IntelliJ.

| Reference file | In IntelliJ |
|---|---|
| `persistence/SlotBucket.java` | click `persistence` → ⌘V (new) |
| `persistence/Schema.java` | open `Schema` → ⌘A ⌘V (replace) |
| `service/LockingStrategy.java` | click `service` → ⌘V (new) |
| `service/PessimisticResourceLock.java` | click `service` → ⌘V (new) |
| `service/SlotBucketFence.java` | click `service` → ⌘V (new) |
| `service/RetryPolicy.java` | open `RetryPolicy` → ⌘A ⌘V (replace) |
| `service/ReservationService.java` | open `ReservationService` → ⌘A ⌘V (replace) |
| `config/ReservationConfig.java` | open `ReservationConfig` → ⌘A ⌘V (replace) |

**SAY** while pasting `PessimisticResourceLock`: "Candidate one, `PessimisticResourceLock.acquire`, calls
`resources.lockById`, a `SELECT FOR UPDATE` on the parent resource row, and then re-runs `findConflict`
under the lock. The parent row always exists, so there is always something to lock, and the lock is in
the shared database. The cost is that every booking on the court queues on one row."

**SAY** while pasting `SlotBucketFence`: "Candidate two, `SlotBucketFence.acquire`, upserts and locks only
the 15-minute `slot_bucket` rows the interval touches, in ascending order so there are no deadlocks, and
stops at the first bucket that already holds an overlapping booking. Upserting gives me a row to lock even
when the rival booking doesn't exist yet. `Fence.claim` is the optimistic part: an `UPDATE ... WHERE
version = :seen` on every bucket, so a moved fencing token aborts the transaction."

**SAY** while pasting `ReservationService`: "The new `ReservationService.createInTransaction` doesn't care
which candidate runs: `locking.acquire` takes the locks, `lease.clash` is the authoritative overlap check,
and `lease.claim` runs after the conditional insert. The reservation id comes from the Idempotency-Key, and
the card hold and the event go into the outbox in the same transaction, so a rollback leaves nothing behind.
`RetryPolicy` retries only lock timeouts and transient errors, caps retries at two in code, and answers
503 with Retry-After when the budget is spent."

### 3.3 Build: the integration point shows up
**DO**:
```bash
./scripts/build.sh
```
**EXPECT**: one error, `ServiceNode.java:31: error: constructor ReservationService in class ReservationService
cannot be applied to given types`, with `required: ... SlotAvailabilityChecker,LockingStrategy,RetryPolicy,ServiceMetrics`.

**SAY**: "One compile error, in `ServiceNode`: the new `ReservationService` constructor needs a
`LockingStrategy`. Wiring the strategy into each pod is the integration the reference patch leaves to me."

### 3.4 ✍️ T2: integration in `ServiceNode`
**DO**: In `cluster/ServiceNode.java`, replace the three lines that start with
`this.service = new ReservationService(` with:
```java
        SlotAvailabilityChecker availability = new SlotAvailabilityChecker(reservations);
        LockingStrategy locking = switch (config.lockingStrategy()) {
            case "pessimistic" -> new PessimisticResourceLock(resources, availability);
            case "fenced" -> new SlotBucketFence(config.slotBucketSize());
            default -> throw new IllegalArgumentException("unknown reservation.locking.strategy: " + config.lockingStrategy());
        };
        this.service = new ReservationService(name, db, reservations, resources, outbox, availability,
                locking, RetryPolicy.fromConfig(config), metrics);
```
No import changes: `ServiceNode` already imports `com.acme.reservations.service.*`.

**SAY**: "Every pod now builds its `LockingStrategy` from `reservation.locking.strategy`: `pessimistic`
gives `PessimisticResourceLock`, `fenced` gives `SlotBucketFence` with the configured bucket size. An
unknown value fails at startup instead of silently running without locks. `ReservationService` no longer
receives the payment gateway or the event bus; the outbox relay delivers those after COMMIT."

### 3.5 ✍️ T3: timeout / retry wiring in `reservation.properties`
**DO**: In `config/reservation.properties`, replace the `# --- Reservation path ---` line and the six lines
below it (down to `retry.backoff.ms=10`, the end of the file) with:
```properties
# --- Reservation path (ADR-007) -------------------------------------------
reservation.locking.strategy=pessimistic
slot.bucket.minutes=15
retry.max.retries=2
retry.backoff.base.ms=2
retry.backoff.max.ms=20
retry.client.retry-after.ms=200
```
**SAY**: "`reservation.local.lock.enabled` is gone, because a JVM lock can't coordinate pods.
`retry.max.retries` goes from 5 to 2, the SRE budget. `retry.backoff.base.ms=2` and `retry.backoff.max.ms=20`
give exponential backoff with full jitter, so a request that uses both retries still answers in about
160 milliseconds. `retry.client.retry-after.ms=200` is the Retry-After for the 503. The lock wait itself stays at
`db.lock.wait.timeout.ms=50`. I start with `reservation.locking.strategy=pessimistic`, the simpler candidate."

### 3.6 ✍️ T4: constraint assertions in the harness
**DO**: In `LockingBenchmark.checkConstraints`, replace the `// TODO` comment and `return null;` with:
```java
        return new Verdict()
                .equal("double bookings (overlap race + 20x burst)", 0, r.doubleBookings())
                .equal("500 responses (exception leaked to the client)", 0, r.serverErrors500())
                .equal("503 responses (retry budget exhausted)", 0, r.busy503())
                .atMost("max single lock wait", Constraints.lockWaitLimitMillis(), r.maxLockWaitMs(), "ms")
                .atMost("lock_timeout aborts per request", Constraints.MAX_RETRY_AMPLIFICATION - 1,
                        r.lockTimeoutsPerRequest(), "")
                .check("max attempts per request <= " + Constraints.MAX_ATTEMPTS,
                        r.maxAttempts() <= Constraints.MAX_ATTEMPTS, "got " + r.maxAttempts())
                .atMost("retry amplification (attempts / requests)", Constraints.MAX_RETRY_AMPLIFICATION,
                        r.amplification(), "x");
```
**SAY**: "One assertion per hard constraint from `Constraints.java`. Zero double bookings across the race
and the burst. No 500s and no 503s, so every client gets a real answer. The longest single lock wait must
stay within the 50 millisecond `lock_timeout` plus 2 milliseconds of wake-up slack. Every lock_timeout
abort costs one extra attempt, so with an amplification budget of 1.10x, at most 0.10 aborts per request
are affordable. At most three attempts, meaning two retries, per request. And total attempts divided by
requests must stay at or under 1.10x."

### 3.7 Build and run both candidates
**DO**:
```bash
./scripts/build.sh
./scripts/benchmark.sh pessimistic fenced | tee reports/candidates-benchmark.txt
```
**EXPECT**: `[build] OK  (40 main / 18 test sources)`, then both strategy blocks and the summary table
(`expected-output/3-candidates-benchmark.txt`, about 3 seconds):
* `pessimistic`: race 0, burst double bookings 0, **lock timeouts ≈ 87–92 per round**, max wait ≈ 50.3 ms,
  max attempts 2, **amplification ≈ 1.67–1.71x**, cold max ≈ 65 ms; two ✘ checks; `REJECT - 2 check(s) failed`.
* `fenced`: race 0, burst double bookings 0, lock timeouts 0, max wait ≈ 15–40 ms, max attempts 1,
  amplification 1.00x, cold max ≈ 5–7 ms; all ✔; `ACCEPT`.
* `RESULT: accepted=[fenced]  rejected=[pessimistic]`

---

## PHASE 4: Rule out the failing option, finalize, verify (≈ 7 min)

### 4.1 Read the measured results
**DO**: Scroll to the `pessimistic` constraint checks, then the summary table.

**SAY**:
* "Both candidates are correct: zero double bookings in the overlap race and in all three burst rounds,
  on three pods. Correctness alone doesn't decide between the two candidates."
* "`pessimistic` fails two checks. About 90 requests per burst round wait the full 50 milliseconds on the
  resource row and get aborted by `lock_timeout`, about 0.7 aborts per request against an affordable 0.10.
  Every abort becomes a retry, so retry amplification is about 1.7x against the 1.10x budget. Even the
  cold slots that nobody competes for take about 65 milliseconds, because every booking on Centre Court
  queues on the same row. Lock granularity is too coarse for a 50 millisecond lock timeout at 20x burst."
* "`fenced` passes every check: zero lock timeouts, the worst single lock wait well under 50 milliseconds,
  one attempt per request, 1.00x amplification, and cold slots in about 5 milliseconds, because requests
  for different times lock different bucket rows."

### 4.2 (Optional) Cross-check with the existing suites
**DO**:
```bash
./scripts/test.sh burst timeout
```
**EXPECT**: `RESULT: 6 passed, 1 failed`: `BurstContentionTest.lockWaitAndRetryBudget` fails on retry
amplification ≈ 1.57x (`expected-output/3b-pessimistic-burst-timeout-tests.txt`).

**SAY**: "The existing `BurstContentionTest` agrees: with `pessimistic` configured,
`lockWaitAndRetryBudget` fails on retry amplification. Two independent measurements rule out `pessimistic`."

### 4.3 ✍️ T5: finalize the winner
**DO**: In `config/reservation.properties`, change `reservation.locking.strategy=pessimistic` to:
```properties
reservation.locking.strategy=fenced
```
**SAY**: "Decision: `fenced`, slot-bucket fencing with a version-guarded claim. `PessimisticResourceLock`
stays behind the same `LockingStrategy` interface as a measured fallback, but production runs `fenced`."

### 4.4 Record the decision
**DO**: Chrome → `reference/docs/adr/ADR-007-reservation-locking.md` → **Copy raw file**. IntelliJ → right-click
`docs` → *New ▸ Directory* `adr` → *New ▸ File* `ADR-007-reservation-locking.md` → ⌘V. Update the numbers in
the *Options measured* table to match `reports/candidates-benchmark.txt`.

**SAY**: "`ADR-007` records the five defects, the two candidates with the numbers from
`reports/candidates-benchmark.txt`, the decision for `fenced`, and the consequences: bucket cleanup,
a backfill before rollout, and events arriving up to one outbox relay interval after COMMIT."

### 4.5 Full verification
**DO**:
```bash
./scripts/test.sh | tee reports/final-tests.txt
./scripts/benchmark.sh | tee reports/final-benchmark.txt
```
**EXPECT**: `RESULT: 27 passed, 0 failed` (`expected-output/4-final-tests.txt`); the benchmark runs the configured
strategy `fenced` and prints `ACCEPT` and `RESULT: accepted=[fenced]` (`expected-output/5-final-benchmark.txt`).

**SAY**: "The full suite gives 27 passed, 0 failed, including the six suites that failed at the start.
`stalledHolderBoundedWaiter` now answers HTTP 503 with Retry-After after three attempts in about 160
milliseconds, instead of HTTP 500 after six attempts. The burst has zero double bookings and zero orphaned
side effects. And the benchmark on the configured strategy, `fenced`, accepts every constraint."

### 4.6 Show what changed
**DO**: Right-click the project root → *Local History ▸ Show History*. Then open `reports/`.

**SAY**: "From the reference patch I reused `LockingStrategy`, `PessimisticResourceLock`, `SlotBucketFence`,
`SlotBucket`, `RetryPolicy`, the new `ReservationService` and the `LockingBenchmark` harness. I wrote the
predicate fix in `SlotAvailabilityChecker`, the strategy wiring in `ServiceNode`, the timeout and retry
settings in `reservation.properties`, and the constraint assertions in `LockingBenchmark.checkConstraints`.
The `reports/` folder holds the baseline, both candidates side by side, and the final verification."

---

## Closing (≈ 30 s)
**SAY**: "To sum up: the double bookings came from a wrong overlap predicate and a check-then-insert race
that a JVM-local lock can't stop across pods. A version counter alone can't protect rows that don't exist
yet, so both candidates lock a row that always exists. I built a race and 20x burst harness, implemented a
pessimistic resource-row lock and a fenced bucket lock behind one `LockingStrategy`, and measured both
against the 50 millisecond lock timeout and the two-retry budget. The pessimistic lock was correct but
breached the lock-timeout and retry-amplification budgets at 20x; the fenced design met every constraint,
so `fenced` is the production strategy and all 27 tests pass."

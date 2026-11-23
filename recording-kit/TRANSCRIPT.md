# Recording transcript: reservation-service locking investigation (IntelliJ IDEA + GitHub reference patch)

Each step has three parts:

* **DO**: what to click, or what to type. Every action is done with the mouse: menus, right-click menus,
  the Project tool window, the gutter line numbers and toolbar buttons. The keyboard is only used to type
  code and terminal commands.
* **SAY**: the narration, written to be read aloud word for word. Every SAY line names the exact file,
  class, method, counter or number on screen, so you never have to point at "this" or "that".
* **EXPECT**: what appears on screen. Numbers vary slightly from run to run; the pass/fail pattern does not.

## What is pasted and what is typed

**Pasted from the reference patch** (`https://github.com/Recorder20102026/recording-kit/tree/main/reference`):
the larger blocks: the candidate locking structures `SlotBucket`, `Schema`, `LockingStrategy`,
`PessimisticResourceLock`, `SlotBucketFence`, `ReservationService`; the bounded `RetryPolicy` and
`ReservationConfig`; the `LockingBenchmark` skeleton (`main` only); and the `ADR-007` template.

**Typed on camera (✍️ H1–H9)**: the workload and assertion parts of the race / 20x burst harness, built on
the existing `testkit` classes, the retry and timeout values, and the service integration:

| ✍️ | Step | File | What you type |
|---|---|---|---|
| H1 | 2.1 | `testkit/Fixtures.java` | `cluster(ReservationConfig, int)`: a cluster for any strategy (6 lines) |
| H2 | 2.2 | `testkit/Workloads.java` | `OVERLAP_RACE` and `roundRobin`: requests spread over the pods (14 lines) |
| H3 | 2.3 | `testkit/RunReport.java` | `lockTimeoutsPerRequest()` counter (5 lines) |
| H4 | 2.4 | `testkit/LockingBenchmark.java` | into the pasted skeleton: `overlapRace`, `burst`, `checkConstraints` (~40 lines) |
| H5 | 2.5 | `scripts/benchmark.sh` | copy of `test.sh`, 2 comment lines + 1 class name |
| H6 | 3.1 | `service/SlotAvailabilityChecker.java` | half-open overlap predicate (4 lines) |
| H7 | 3.4 | `cluster/ServiceNode.java` | strategy wiring per pod (8 lines) |
| H8 | 3.5 | `config/reservation.properties` | retry cap, jitter window, Retry-After, strategy (7 lines) |
| H9 | 4.3 | `config/reservation.properties` | `pessimistic` → `fenced` (1 word) |

**Mouse techniques used below**

* **Select whole lines**: click the line number of the first line in the gutter, hold the mouse button, drag
  down to the last line number, release. Typing then replaces the selected lines, including the line break
  of the last one, so **press Return after the last line you type**.
* **Indentation and brackets**: IntelliJ indents new lines and adds closing brackets and quotes as you type.
  Before recording, untick *Settings ▸ Editor ▸ General ▸ Smart Keys ▸ Insert paired brackets* and
  *Insert pair quote*. If indentation drifts, select the block in the gutter and use menu
  **Code ▸ Auto-Indent Lines**.
* **Select one word**: double-click the word. Typing replaces the word.
* **Paste a new class from GitHub**: in Chrome click **Copy raw file** (the two-squares icon at the top right
  of the file view). In IntelliJ right-click the target package in the Project tool window ▸ **Paste**.
  IntelliJ creates the `.java` file with the right name.
* **Replace an existing file from GitHub**: open the file by double-clicking it in the Project tool window,
  then menu **Edit ▸ Select All**, then menu **Edit ▸ Paste**.
* **Terminal**: click the **Terminal** icon in the tool window bar (bottom left), or menu
  **View ▸ Tool Windows ▸ Terminal**.

Reference outputs from a real run are in `recording-kit/expected-output/`. Total running time is about 35 minutes.

---

## PHASE 1: Extract, open, run the baseline, inspect the reference patch (≈ 8 min)

### 1.1 Extract the archive
**DO**: In Finder, drag `input 1.zip` into an empty working folder (e.g. `~/recording/`) and double-click `input 1.zip`.

**SAY**: "I've been handed a brownfield reservation service as the archive `input 1.zip`. The open
incident is INCIDENT-2291: overlapping time-range double bookings during a hot-slot release, on a service
that runs on several pods. I'll extract `input 1.zip` and open the `input 1` folder in IntelliJ IDEA."

### 1.2 Open in IntelliJ IDEA
**DO**: IntelliJ menu **File ▸ Open…** → click the extracted `input 1` folder → click **Open** → click
**Trust Project**. Menu **File ▸ Project Structure…** → **Project** shows SDK **25** → click **Cancel**.
In the Project tool window click the arrows to expand `src/main/java` and `src/test/java`.

**SAY**: "The `input 1` project is plain Java with no Maven or Gradle; `scripts/build.sh` compiles the
project with `javac`. The main code is in `com.acme.reservations`: `service` holds `ReservationService`,
`persistence` holds the repositories, `outbox` is the transactional outbox, and `db` models our shared
PostgreSQL primary, including row locks and a real `lock_timeout`, so the tests measure real lock waits.
`src/test/java` has six test suites and a `testkit` package: `LoadRunner` fires requests at the same
instant, `Workloads` has the hot-slot burst, `RunReport` collects counters, `Invariants` finds double
bookings, and `Verdict` collects assertions. I'll build the benchmark harness on top of those classes."

### 1.3 Read the incident and the constraints
**DO**: In the Project tool window double-click `docs/INCIDENT-2291.md`, scroll to the *Constraints* table.
Then double-click `src/test/java/com/acme/reservations/testkit/Constraints.java`.

**SAY**: "`INCIDENT-2291.md` lists the hard constraints. We run at least three pods. Any partial overlap
must be rejected, including against rows that don't exist yet when we read. The DBA set `lock_timeout` to
50 milliseconds. SRE allows at most two retries and a retry amplification of 1.1x. Hot slots see 20x
concurrent demand. `Constraints.java` holds the same rules as `NODES = 3`, `LOCK_WAIT_BUDGET = 50 ms`,
`MAX_RETRIES = 2`, `MAX_RETRY_AMPLIFICATION = 1.10` and `BURST_FACTOR = 20`. `Constraints.java` is what
I'm measured against; `config/reservation.properties` is what I'm allowed to change."

### 1.4 Look at the current config
**DO**: Double-click `config/reservation.properties`.

**SAY**: "In `reservation.properties`, `db.lock.wait.timeout.ms=50` is the DBA's lock timeout.
`reservation.local.lock.enabled=true` is the RES-212 hotfix, a lock inside the JVM. And
`retry.max.retries=5` with a fixed 10 millisecond backoff already breaks the two-retry rule."

### 1.5 Run the baseline suite in the IntelliJ terminal
**DO**: Click the **Terminal** icon and type:
```bash
./scripts/test.sh | tee reports/baseline-tests.txt
```
**SAY**: "`scripts/test.sh` builds the project and runs all six suites: `OverlappingIntervalTest`,
`BurstContentionTest`, `MultiNodeConsistencyTest`, `PartialFailureTest`, `LockTimeoutRetryTest` and
`RegressionTest`. `tee` saves the output to `reports/baseline-tests.txt`."

**EXPECT**: `RESULT: 17 passed, 10 failed`, then the list of 10 failures (`expected-output/1-baseline-tests.txt`).

### 1.6 Read the failure modes (scroll the terminal with the mouse wheel)
**SAY**, while scrolling:
* "`OverlappingIntervalTest.leftPartialOverlapRejected` and `enclosingIntervalRejected` get HTTP 201 instead
  of 409: 09:30 to 10:30 against an existing 10:00 to 11:00 booking is accepted. The overlap predicate is
  wrong even single-threaded. `concurrentPartialOverlapsAcrossNodes` double books in 10 out of 10 rounds."
* "`BurstContentionTest.noDoubleBookings` finds overlapping confirmed reservations after the 20x burst."
* "`MultiNodeConsistencyTest.sameSlotAcrossThreeNodes` gets three winners for one slot, one winner per pod.
  The RES-212 lock lives in each pod's JVM, so the three pods never see each other's lock."
* "`LockTimeoutRetryTest.stalledHolderBoundedWaiter` waits 50 milliseconds per attempt, tries six times,
  takes about 420 milliseconds and returns HTTP 500. Six attempts breaks the two-retry rule."
* "`PartialFailureTest` shows orphaned card holds, because the payment call runs before COMMIT."

### 1.7 Confirm the two root causes in the code
**DO**: Double-click `service/SlotAvailabilityChecker.java` and read `findConflict` (lines 23–33). Then
double-click `service/ReservationService.java`, right-click the field name `resourceLocks` on line 36 ▸
**Find Usages**.

**SAY**: "`SlotAvailabilityChecker.findConflict` only asks whether the new booking **starts** during an
existing one, which misses left-partial and enclosing overlaps. `findConflict` is also a plain non-locking
read, so two pods can both pass the check before either one inserts. Find Usages on `resourceLocks` shows
a `ConcurrentHashMap` of `ReentrantLock` objects inside `ReservationService`, one `ReservationService` per
pod. A JVM-local lock cannot coordinate three pods; the lock has to live in the shared database."

### 1.8 Inspect the reference patch on GitHub
**DO**: Click the Chrome window → `https://github.com/Recorder20102026/recording-kit/tree/main/reference` →
scroll `README.md`, then click `reference.patch` and scroll through the file list.

**SAY**: "The platform team's reference patch has the larger locking structures: `LockingStrategy`, the
interface; `PessimisticResourceLock`, a `SELECT FOR UPDATE` on the parent resource row; `SlotBucketFence`
with `SlotBucket`, which locks only the time buckets an interval touches and claims them with a version
guard; a `ReservationService` driven by a `LockingStrategy`; a bounded `RetryPolicy` with its
`ReservationConfig` keys; and a `LockingBenchmark` skeleton that only has `main`. I'll adapt those blocks.
The reference says plainly what it does not contain: the race and burst workloads and the constraint
assertions of the harness, the retry and timeout values for our service, and the integration into our pods.
I'll write those myself, starting with the harness, because I need numbers before I pick a design."

**SAY**: "Both candidates keep their locks in the database, so both survive multiple pods. Neither one
relies on a version counter alone: a version counter on reservation rows can't protect a partial overlap,
because the rival row doesn't exist yet when I read. What separates the two candidates is lock granularity,
and lock granularity decides lock-wait timeouts and retry amplification. I'll measure that, not guess it."

---

## PHASE 2: Build the race / 20x burst harness by extending the testkit (≈ 10 min)

### 2.1 ✍️ H1: `Fixtures.cluster(ReservationConfig, int)`
**DO**: Double-click `testkit/Fixtures.java`. In the gutter, drag from line **34** to line **38** (the method
`public static Cluster cluster(int nodes) {` down to its closing `}`) and type:
```java
    public static Cluster cluster(int nodes) {
        return cluster(config(), nodes);
    }

    /** The same deployment with an explicit config, e.g. another reservation.locking.strategy. */
    public static Cluster cluster(ReservationConfig config, int nodes) {
        return Cluster.start(config, nodes)
                .withResource(Resource.active(HOT_COURT, "Centre Court", 4_000))
                .withResource(Resource.active(OTHER_COURT, "Court 2", 3_000));
    }
```
**SAY**: "`Fixtures.cluster` builds a three-pod cluster with Centre Court and Court 2, but `Fixtures.config`
caches `reservation.properties` once per JVM. The benchmark has to start a fresh cluster per strategy, so I
add `cluster(ReservationConfig config, int nodes)`, and the old `cluster(int nodes)` delegates to it. Every
existing test keeps the same behaviour."

### 2.2 ✍️ H2: `Workloads.OVERLAP_RACE` and `Workloads.roundRobin`
**DO**: Double-click `testkit/Workloads.java`. Click at the end of line **35**
(`slot("19:00", "19:30"), slot("19:30", "20:00"));`), press Return twice and type:
```java
    /** Cross-pod race: right-partial and left-partial overlaps of 10:00-11:00, fired at the same instant. */
    public static final List<TimeInterval> OVERLAP_RACE = List.of(
            slot("10:00", "11:00"), slot("10:30", "11:30"), slot("09:30", "10:30"));
```
Then click at the end of line **42**, the `}` that closes `private Workloads() {`, press Return twice and type:
```java
    /** Request i goes to pod i % pods, like the load balancer's round robin. */
    public static List<LoadRunner.Submission> roundRobin(Cluster cluster, List<CreateReservationRequest> requests) {
        List<LoadRunner.Submission> submissions = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            ServiceNode node = cluster.node(i % cluster.nodes().size());
            submissions.add(new LoadRunner.Submission(requests.get(i), node.name(), node.controller()::postReservation));
        }
        return submissions;
    }
```
Then click at the end of line **2** (the empty line after `package ...;`), press Return and type:
```java
import com.acme.reservations.cluster.*;
```
**SAY**: "`OVERLAP_RACE` is the incident in three intervals: 10:00 to 11:00, 10:30 to 11:30, which overlaps on
the right, and 09:30 to 10:30, which overlaps on the left, the case the old predicate misses. Exactly one of
the three may win. `roundRobin` sends request `i` to pod `i % 3`, like the load balancer, so every race and
every burst crosses pods. A lock that only works inside one JVM can't pass a test that uses `roundRobin`."

### 2.3 ✍️ H3: `RunReport.lockTimeoutsPerRequest()`
**DO**: Double-click `testkit/RunReport.java`. Click at the end of line **65** (the `}` that closes
`retryAmplification()`), press Return twice and type:
```java
    /** Every lock_timeout abort costs one extra attempt, so this is bounded by amplification - 1. */
    public double lockTimeoutsPerRequest() {
        return requests == 0 ? 0 : (double) db.lockTimeouts() / requests;
    }
```
**SAY**: "`RunReport` already counts double bookings, 409s, 503s and 500s, attempts, retries, the database
lock-timeout count, the maximum lock wait and p50 and p99 latency. What `RunReport` doesn't have is the ratio
I need for the 50 millisecond rule: lock-timeout aborts per request. Every `lock_timeout` abort turns into
one more attempt, so with an amplification budget of 1.10x, at most 0.10 aborts per request are affordable."

### 2.4 ✍️ H4: the harness, `LockingBenchmark`
**DO**: Chrome → `reference/src/test/java/com/acme/reservations/testkit/LockingBenchmark.java` → click
**Copy raw file**. IntelliJ → right-click the `testkit` package under `src/test/java` ▸ **Paste**. IntelliJ
creates `LockingBenchmark.java`. Scroll to line **45**,
`// Written in the service itself: overlapRace (cross-pod overlap race), burst (20x burst), checkConstraints.`

**SAY**: "The reference skeleton `LockingBenchmark` only has `main`. `main` takes strategy names, for example
`pessimistic fenced`, sets the system property `reservation.locking.strategy` for each one and loads a fresh
`ReservationConfig`; with no names, `main` runs whatever `reservation.properties` configures, which today is
the baseline. Each strategy gets its own `Verdict`, the soft-assertion class from our testkit: every check is
printed, and `assertAll` decides ACCEPT or REJECT. `main` calls `overlapRace` and `burst`, which don't exist
yet: the workloads and the assertions are specific to our service, so I write them."

**DO**: In the gutter, click line number **45** (the `// Written in the service itself…` comment) to select
the line, then type parts B, C and D below one after another, and press Return after the last line.

**Part B: the race round**
```java
    /** Three partially overlapping requests at the same instant, one per pod, RACE_ROUNDS times. */
    static void overlapRace(ReservationConfig config, Verdict verdict) {
        int roundsWithDoubleBooking = 0;
        for (int round = 1; round <= RACE_ROUNDS; round++) {
            Cluster cluster = Fixtures.cluster(config, Constraints.NODES);
            List<CreateReservationRequest> race = Workloads.OVERLAP_RACE.stream().map(Fixtures::request).toList();
            LoadRunner.fireTogether(Workloads.roundRobin(cluster, race));
            if (!Invariants.doubleBookings(cluster.db()).isEmpty()) {
                roundsWithDoubleBooking++;
            }
        }
        verdict.equal("overlap race: rounds with a double booking", 0, roundsWithDoubleBooking);
    }
```
**SAY**: "`overlapRace` is the concurrent interval-overlap test. Each of the 10 rounds starts a fresh
three-pod cluster, sends the three `OVERLAP_RACE` requests through `roundRobin`, one per pod, and
`LoadRunner.fireTogether` releases all three threads from one latch at the same instant. A race is
probabilistic, so one clean round proves nothing; 10 rounds make a lucky pass unlikely. The counter
`roundsWithDoubleBooking` uses `Invariants.doubleBookings`, which reads committed rows in the database, not
what the service claims. The limit is zero: one double-booked round rejects the strategy."

**Part C: the burst loop**
```java

    /** 20 requests per hot slot + 1 per cold slot, round robin over the pods. Round 0 is a JIT warm-up. */
    static void burst(ReservationConfig config, Verdict verdict) {
        for (int round = 0; round <= BURST_ROUNDS; round++) {
            Cluster cluster = Fixtures.cluster(config, Constraints.NODES);
            List<CreateReservationRequest> requests = Workloads.hotSlotBurst(Constraints.BURST_FACTOR).all(round);
            LoadRunner.Run run = LoadRunner.fireTogether(Workloads.roundRobin(cluster, requests));
            if (round == 0) {
                continue;
            }
            RunReport report = RunReport.of("20x burst, round " + round, cluster, run).print();
            checkConstraints(report, verdict);
        }
    }
```
**SAY**: "`burst` is the 20x test. `Workloads.hotSlotBurst(Constraints.BURST_FACTOR)` builds 20 identical
requests for each of the six partially overlapping hot slots, plus one request for each of ten cold slots,
130 requests in all, shuffled with the round number as the seed. Round 0 is thrown away: a cold JVM runs
in the interpreter, which stretches lock hold times and says nothing about the design. Rounds 1 to 3 each
run on a fresh cluster. `RunReport.of` collects every counter, `print` shows them, and `checkConstraints`
asserts them, so each of the three rounds has to pass on its own."

**Part D: the constraint assertions**
```java

    /** One assertion per hard constraint in Constraints.java. */
    static void checkConstraints(RunReport r, Verdict verdict) {
        verdict.equal("double bookings", 0, r.doubleBookings())
                .equal("HTTP 500 responses", 0, r.serverErrors500())
                .equal("HTTP 503 responses", 0, r.busy503())
                .atMost("max single lock wait", Constraints.lockWaitLimitMillis(), r.db().maxLockWaitMillis(), " ms")
                .atMost("lock_timeout aborts per request", Constraints.MAX_RETRY_AMPLIFICATION - 1,
                        r.lockTimeoutsPerRequest(), "")
                .check("max attempts per request <= " + Constraints.MAX_ATTEMPTS,
                        r.maxAttemptsPerRequest() <= Constraints.MAX_ATTEMPTS, "got " + r.maxAttemptsPerRequest())
                .atMost("retry amplification", Constraints.MAX_RETRY_AMPLIFICATION, r.retryAmplification(), "x");
    }
```
**SAY**, one sentence per assertion:
* "`double bookings` must be 0: the correctness rule, checked on committed rows."
* "`HTTP 500 responses` must be 0: a 500 means an exception leaked to the client, which is what the
  baseline does after six attempts."
* "`HTTP 503 responses` must be 0 under this burst: a 503 means a request spent its whole retry budget, so
  the design is too slow for 20x."
* "`max single lock wait` must stay within `Constraints.lockWaitLimitMillis()`, the 50 millisecond
  `lock_timeout` plus 2 milliseconds for the thread to wake up. No request may wait longer for a row lock."
* "`lock_timeout aborts per request` must stay at or below `MAX_RETRY_AMPLIFICATION - 1`, so 0.10. Waits that
  hit exactly 50 milliseconds are aborted, and every abort costs a retry."
* "`max attempts per request` must be at most `Constraints.MAX_ATTEMPTS`, three: the first attempt plus the
  two retries SRE allows."
* "`retry amplification`, total database attempts divided by client requests, must stay at or below 1.10x,
  so a burst can't turn into a retry storm on the primary."

### 2.5 ✍️ H5: `scripts/benchmark.sh`, from `scripts/test.sh`
**DO**: In the Project tool window right-click `scripts/test.sh` ▸ **Copy**. Right-click the `scripts`
folder ▸ **Paste** → in the *Copy* dialog, change *New name* to `benchmark.sh` → click **OK**. In
`benchmark.sh` drag in the gutter from line **2** to line **5** (the four `#` comment lines) and type:
```bash
# Race / 20x burst benchmark for candidate locking strategies.
#   ./scripts/benchmark.sh pessimistic fenced     -> each candidate on the same workload
```
On the last line, double-click the word `TestRunner` and type `LockingBenchmark`. Then in the terminal:
```bash
chmod +x scripts/benchmark.sh
```
**SAY**: "`benchmark.sh` is `test.sh` with the main class changed from `TestRunner` to `LockingBenchmark`: it
builds, then runs the harness with the same fixed heap and parallel GC, so GC pauses stay out of the
lock-wait numbers."

### 2.6 Measure the baseline
**DO**:
```bash
./scripts/benchmark.sh | tee reports/baseline-benchmark.txt
```
**EXPECT** (`expected-output/2-baseline-benchmark.txt`): `[build] OK (36 main / 18 test sources)`;
strategy `configured`; `✘ overlap race: rounds with a double booking  expected 0, got 10`; three burst rounds,
each with `double bookings` 2–7 (✘), `lock timeouts=0`, `max wait` ≈ 3–4 ms, p99 ≈ 95–125 ms;
`verdict: REJECT, 4 check(s) failed`; `RESULT: accepted=[]  rejected=[configured]`.

**SAY**: "The harness reproduces INCIDENT-2291 on demand: 10 out of 10 race rounds double book across three
pods, and every burst round double books. The database lock timeouts are zero and the maximum database lock
wait is about 4 milliseconds, yet p99 latency is around 100 milliseconds. The time is spent queueing on the
JVM-local `ReentrantLock`, not in the database. `checkConstraints` rejects the baseline with four failed
checks, all double bookings. That's the baseline the two candidates have to beat."

---

## PHASE 3: Implement both candidate locking designs (≈ 10 min)

### 3.1 ✍️ H6: fix the overlap predicate (shared by both candidates)
**DO**: Double-click `service/SlotAvailabilityChecker.java`. In the gutter, drag from line **25**
(`// A booking clashes when…`) to line **29** (`if (startsDuringExisting) {`) and type:
```java
            // Half-open overlap: catches left/right partial, contained, enclosing and identical intervals.
            if (existing.interval().overlaps(requested)) {
```
Then menu **Code ▸ Optimize Imports** removes the now-unused `import java.time.Instant;`.

**SAY**: "Both candidates re-check overlap, so both need a correct predicate. `TimeInterval.overlaps` is
the half-open test, start before the other's end and end after the other's start, and it already exists."

### 3.2 Paste the candidate structures from the reference patch
**DO**: For each file: in Chrome open `reference/src/main/java/com/acme/reservations/...` → click **Copy raw
file** → in IntelliJ:

| Reference file | In IntelliJ |
|---|---|
| `persistence/SlotBucket.java` | right-click package `persistence` ▸ **Paste** (new) |
| `persistence/Schema.java` | double-click `Schema.java` ▸ **Edit ▸ Select All** ▸ **Edit ▸ Paste** (replace) |
| `service/LockingStrategy.java` | right-click package `service` ▸ **Paste** (new) |
| `service/PessimisticResourceLock.java` | right-click package `service` ▸ **Paste** (new) |
| `service/SlotBucketFence.java` | right-click package `service` ▸ **Paste** (new) |
| `service/ReservationService.java` | double-click `ReservationService.java` ▸ **Edit ▸ Select All** ▸ **Edit ▸ Paste** (replace) |
| `service/RetryPolicy.java` | double-click `RetryPolicy.java` ▸ **Edit ▸ Select All** ▸ **Edit ▸ Paste** (replace) |
| `config/ReservationConfig.java` | double-click `ReservationConfig.java` ▸ **Edit ▸ Select All** ▸ **Edit ▸ Paste** (replace) |

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
`createReservation` hands each attempt to `retryPolicy.execute`."

**SAY** while pasting `RetryPolicy`, with the editor on `HARD_MAX_RETRIES` and `execute`:
* "`HARD_MAX_RETRIES = 2` is the SRE budget, and the constructor clamps `maxRetries` with
  `Math.min(maxRetries, HARD_MAX_RETRIES)`. The baseline read `retry.max.retries=5` straight from config;
  now a config typo can't create a retry storm."
* "`execute` catches only `LockWaitTimeoutException`, which is Postgres error 55P03 after our 50
  millisecond `lock_timeout`, and `TransientDbException`. A 409 Conflict is never retried, because a
  conflict is a returned business answer, not an exception: another customer owns the slot, and asking
  again in 10 milliseconds can't change that. Retrying a conflict would only add load to the primary
  during exactly the burst we're trying to survive."
* "When the budget is spent, `execute` returns `ReservationResult.Busy` with `clientRetryAfter`, and
  `HttpResponse` turns `Busy` into HTTP 503 with `retryAfterMs`, instead of the HTTP 500 the baseline leaked."
* "`backoff` uses full jitter: a uniform random sleep between zero and `base * 2^(n-1)`, capped at
  `maxBackoff`. The baseline slept a fixed 10 milliseconds, so every loser came back at the same instant."

**SAY** while pasting `ReservationConfig`: "`ReservationConfig` drops `localLockEnabled` and adds
`retryBackoffBase`, `retryBackoffMax`, `clientRetryAfter`, `slotBucketSize` and `lockingStrategy`. The system
property `reservation.locking.strategy` wins over the file, which is how `LockingBenchmark` switches strategy."

### 3.3 Build: the integration point shows up
**DO**:
```bash
./scripts/build.sh
```
**EXPECT** (`expected-output/3a-build-after-paste.txt`): `1 error`:
`ServiceNode.java:31: error: constructor ReservationService in class ReservationService cannot be applied to given types`,
with `required: ... SlotAvailabilityChecker,LockingStrategy,RetryPolicy,ServiceMetrics`.

**SAY**: "One compile error, in `ServiceNode` line 31: the new `ReservationService` constructor needs a
`LockingStrategy`. Wiring the strategy into each pod is the service-specific integration I write by hand."

### 3.4 ✍️ H7: integration in `ServiceNode`
**DO**: Double-click `cluster/ServiceNode.java`. In the gutter, drag from line **31**
(`this.service = new ReservationService(…`) to line **33** (`RetryPolicy.fromConfig(config), config, metrics);`) and type:
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

### 3.5 ✍️ H8: retry and timeout values in `reservation.properties`
**DO**: Double-click `config/reservation.properties`. In the gutter, drag from line **21**
(`# --- Reservation path ---…`) to line **27** (`retry.backoff.ms=10`, the end of the file) and type:
```properties
# --- Reservation path (ADR-007) -------------------------------------------
reservation.locking.strategy=pessimistic
slot.bucket.minutes=15
retry.max.retries=2
retry.backoff.base.ms=2
retry.backoff.max.ms=20
retry.client.retry-after.ms=200
```
**SAY**: "These values are the retry wiring for our service. `reservation.local.lock.enabled` is gone,
because a JVM lock can't coordinate pods. `retry.max.retries` goes from 5 to 2, the SRE budget, and
`RetryPolicy.HARD_MAX_RETRIES` would clamp anything higher. `retry.backoff.base.ms=2` and `retry.backoff.max.ms=20`
set the jitter window, so a request that uses both retries still answers in about 160 milliseconds.
`retry.client.retry-after.ms=200` is the Retry-After for the 503. The lock wait itself stays at
`db.lock.wait.timeout.ms=50`. I start with `reservation.locking.strategy=pessimistic`, the simpler candidate."

### 3.6 Build and run both candidates
**DO**:
```bash
./scripts/benchmark.sh pessimistic fenced | tee reports/candidates-benchmark.txt
```
**EXPECT**: `[build] OK  (40 main / 18 test sources)`, then both strategy blocks
(`expected-output/3-candidates-benchmark.txt`, about 3 seconds):
* `pessimistic`: race ✔ 0; each burst round double bookings 0, **lock timeouts ≈ 70–92**, max wait ≈ 50.2 ms,
  max attempts 2, **amplification ≈ 1.55–1.71x**; per round ✘ `lock_timeout aborts per request` (≈ 0.55–0.71,
  limit 0.10) and ✘ `retry amplification`; `verdict: REJECT, 6 check(s) failed`.
* `fenced`: race ✔ 0; each burst round double bookings 0, lock timeouts 0, max wait ≈ 10–40 ms,
  max attempts 1, amplification 1.00x; all ✔; `verdict: ACCEPT`.
* `RESULT: accepted=[fenced]  rejected=[pessimistic]`

---

## PHASE 4: Rule out the failing option, finalize, verify (≈ 8 min)

### 4.1 Read the measured results
**DO**: Scroll the terminal up with the mouse wheel to the `pessimistic` burst rounds, then down to `fenced`.

**SAY**:
* "Both candidates are correct: zero double bookings in the overlap race and in all three burst rounds,
  on three pods. Correctness alone doesn't decide between the two candidates."
* "`pessimistic` fails two checks in every round. Between 70 and 90 requests per burst round wait the full
  50 milliseconds on the resource row and get aborted by `lock_timeout`, about 0.6 aborts per request against
  an affordable 0.10. Every abort becomes a retry, so retry amplification is about 1.6 to 1.7x against the 1.10x
  budget. The retry cap held, max attempts is 2, but the lock granularity is too coarse: every booking on
  Centre Court, even a cold slot nobody competes for, queues on the same row."
* "`fenced` passes every check: zero lock timeouts, the worst single lock wait well under 50 milliseconds,
  one attempt per request, 1.00x amplification, because requests for different times lock different bucket rows."

### 4.2 (Optional) Cross-check with the existing suites
**DO**:
```bash
./scripts/test.sh burst timeout
```
**EXPECT**: `RESULT: 6 passed, 1 failed`: `BurstContentionTest.lockWaitAndRetryBudget` fails on retry
amplification ≈ 1.5–1.6x (`expected-output/3b-pessimistic-burst-timeout-tests.txt`).
`LockTimeoutRetryTest.stalledHolderBoundedWaiter` now passes: HTTP 503 `retryAfterMs: 200` after 3 attempts.

**SAY**: "The existing `BurstContentionTest` agrees: with `pessimistic` configured,
`lockWaitAndRetryBudget` fails on retry amplification. Two independent measurements rule out `pessimistic`.
And `stalledHolderBoundedWaiter` already passes with my `RetryPolicy`: three attempts, then HTTP 503 with a
200 millisecond Retry-After."

### 4.3 ✍️ H9: finalize the winner
**DO**: In `config/reservation.properties` double-click the word `pessimistic` on line 22 and type `fenced`:
```properties
reservation.locking.strategy=fenced
```
**SAY**: "Decision: `fenced`, slot-bucket fencing with a version-guarded claim. `PessimisticResourceLock`
stays behind the same `LockingStrategy` interface as a measured fallback, but production runs `fenced`."

### 4.4 Record the decision
**DO**: Chrome → `reference/docs/adr/ADR-007-reservation-locking.md` → click **Copy raw file**. IntelliJ →
right-click `docs` ▸ **New ▸ Directory** → `adr` → right-click `adr` ▸ **Paste** → name
`ADR-007-reservation-locking.md` → **OK**. Compare the *Options measured* table with
`reports/candidates-benchmark.txt` and fix any number that differs.

**SAY**: "`ADR-007` records the five defects, the two candidates with the numbers from
`reports/candidates-benchmark.txt`, the decision for `fenced`, and the consequences: bucket cleanup,
a backfill before rollout, and events arriving up to one outbox relay interval after COMMIT."

### 4.5 Full verification
**DO**:
```bash
./scripts/test.sh | tee reports/final-tests.txt
./scripts/benchmark.sh | tee reports/final-benchmark.txt
```
**EXPECT**: `RESULT: 27 passed, 0 failed` (`expected-output/4-final-tests.txt`); the benchmark runs the
configured strategy, now `fenced`, all ✔, `verdict: ACCEPT`, `RESULT: accepted=[configured]  rejected=[]`
(`expected-output/5-final-benchmark.txt`).

**SAY**: "The full suite gives 27 passed, 0 failed, including the six suites that failed at the start.
`stalledHolderBoundedWaiter` now answers HTTP 503 with Retry-After after three attempts in about 160
milliseconds, instead of HTTP 500 after six attempts. The burst has zero double bookings and zero orphaned
side effects. And `benchmark.sh` with no arguments runs the configured strategy from
`reservation.properties`, `fenced`, and accepts every constraint."

### 4.6 Show what changed
**DO**: Right-click the project root `input 1` in the Project tool window ▸ **Local History ▸ Show History**.
Click through the entries. Then expand `reports/`.

**SAY**: "From the reference patch I adapted the locking structures `LockingStrategy`,
`PessimisticResourceLock`, `SlotBucketFence`, `SlotBucket`, the `Schema` change and the new
`ReservationService`, plus the bounded `RetryPolicy`, `ReservationConfig` and the `LockingBenchmark` skeleton.
I wrote the harness workloads on top of the existing testkit: `Fixtures.cluster` with an explicit config,
`Workloads.OVERLAP_RACE` and `roundRobin`, `RunReport.lockTimeoutsPerRequest`, and in `LockingBenchmark` the
race round `overlapRace`, the burst loop `burst` and the assertions in `checkConstraints`. I wrote the retry
and timeout values in `reservation.properties`, the strategy wiring in `ServiceNode`, and the predicate fix
in `SlotAvailabilityChecker`. The `reports/` folder holds the baseline, both candidates side by side, and the
final verification."

---

## Closing (≈ 30 s)
**SAY**: "To sum up: the double bookings came from a wrong overlap predicate and a check-then-insert race
that a JVM-local lock can't stop across pods. A version counter alone can't protect rows that don't exist
yet, so both candidates lock a row that always exists. I built a race and 20x burst harness on the existing
testkit, implemented a pessimistic resource-row lock and a fenced bucket lock behind one `LockingStrategy`,
capped retries at two with jitter and a 503 with Retry-After, and measured both candidates against the 50
millisecond lock timeout and the two-retry budget. The pessimistic lock was correct but breached the
lock-timeout and retry-amplification budgets at 20x; the fenced design met every constraint, so `fenced` is
the production strategy and all 27 tests pass."

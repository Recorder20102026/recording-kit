# Recording kit: reservation-service locking investigation

| Path | What it is |
|---|---|
| `../input 1/` | The brownfield project (baseline with the bugs). Open this, or the extracted zip, in IntelliJ IDEA. |
| `../input 1.zip` | Pristine copy of the baseline. Re-extract it to reset before a re-take. |
| `TRANSCRIPT.md` | Step-by-step script: **DO** (clicks / commands), **SAY** (narration), **EXPECT** (what appears). |
| `solution/` | Final version of every file the fix touches, at the same relative paths. Copy or paste from here in Phase 4. |
| `solution.patch` | The whole fix as one unified diff. Recovery: `patch -p1 < "../recording-kit/solution.patch"` from the project root. |
| `expected-output/` | Real outputs from a dry run: baseline tests, race repro, design evaluation, after-fix tests, after-fix repro. |

## Imports

Every source file imports with `package.*` wherever it uses two or more classes from the same package
(for example `import com.acme.reservations.persistence.*;`). The pasted solution files therefore bring
their own imports, and the hand-typed edits (`Schema`, `ServiceNode`, `SlotAvailabilityChecker`) need no
new imports. `input 1/.idea/codeStyles/` sets IntelliJ to the same rule (2+ classes => `.*`), so
auto-import and Optimize Imports (⌃⌥O) keep the files in this style.

## Pre-flight checklist (before you hit record)

1. IntelliJ IDEA: *Settings ▸ Editor ▸ Font* → 16+ pt; the terminal font too (*Settings ▸ Tools ▸ Terminal*).
2. *File ▸ Project Structure ▸ Project SDK* = **25** (any JDK 21+ works).
3. Do one silent dry run of `./scripts/test.sh` in the IntelliJ terminal so macOS has the JDK warmed up and
   nothing pops a permission dialog during the take.
4. Re-extract `input 1.zip` into a fresh folder for the actual take, so the baseline is pristine.
5. Keep `recording-kit/solution/` open in Finder or a second editor window for Phase 4.
6. If you need to reset mid-way: delete the project folder and re-extract the zip.

## What the fix changes (material changes to the input files)

```
config/reservation.properties                     local lock removed, retries 5 -> 2, jittered backoff, bucket size
docs/adr/ADR-007-reservation-locking.md           NEW: decision record with measured evidence
src/main/.../cluster/ServiceNode.java             constructor wiring (service no longer calls payment/events directly)
src/main/.../config/ReservationConfig.java        new settings (backoff base/max, Retry-After, bucket size)
src/main/.../persistence/Schema.java              + slot_bucket table
src/main/.../persistence/SlotBucket.java          NEW: bucket row (version = fencing token, occupants)
src/main/.../service/SlotAvailabilityChecker.java predicate fixed (half-open overlap)
src/main/.../service/SlotBucketFence.java         NEW: scoped bucket locks + version-guarded claim/release
src/main/.../service/RetryPolicy.java             bounded (hard cap 2), full jitter, 503 instead of exception
src/main/.../service/ReservationService.java      idempotent id, fast path, fence, outbox, no JVM lock / hot-row UPDATE
reports/*.txt                                     evidence you capture with `tee` during the recording
```

## Expected results

| | Baseline | After fix |
|---|---|---|
| `./scripts/test.sh` | **17 passed, 10 failed** (same 10 every run) | **27 passed, 0 failed** |
| Burst: double bookings | 2–5 | 0 |
| Burst: p99 latency | ~110 ms | ~25 ms |
| Burst: max lock wait / amplification | n/a (JVM lock queueing) / 1.00x | ~15–30 ms / 1.00x |
| Stalled holder: waiter | 6 attempts, ~430 ms, HTTP 500, 6 orphaned holds | 3 attempts, ~160 ms, HTTP 503 + Retry-After, 0 orphans |
| Design evaluation | A/B: double bookings · C1: ~270 lock timeouts, 1.7x · **C2: accepted** | (unchanged, it benchmarks spikes) |

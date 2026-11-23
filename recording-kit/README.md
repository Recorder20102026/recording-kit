# Recording kit: reservation-service locking investigation

| Path | What it is |
|---|---|
| `../input 1.zip` | Baseline project (bugs included). Extract it fresh before every take. |
| `../input 1/` | The same baseline, browsable on GitHub. |
| `../reference/` | **Reference GitHub patch** shown on camera: the large blocks you copy-paste (see `reference/README.md`). |
| `TRANSCRIPT.md` | Step-by-step script: **DO** / **SAY** / **EXPECT**, with the five ✍️ edits you type by hand. |
| `solution.patch` | Baseline → final, including the hand-typed parts. Recovery: `patch -p1 < solution.patch && chmod +x scripts/benchmark.sh` from the project root. |
| `expected-output/` | Real outputs from a dry run of every phase. |

## Copy-paste vs. typed

* **Copy-paste from `reference/` (Chrome → Copy raw file → IntelliJ ⌘V):** `LockingBenchmark`, `benchmark.sh`,
  `SlotBucket`, `Schema`, `LockingStrategy`, `PessimisticResourceLock`, `SlotBucketFence`, `RetryPolicy`,
  `ReservationService`, `ReservationConfig`, `ADR-007`.
* **Typed on camera (✍️ T1–T5, exact text in `TRANSCRIPT.md`):** overlap predicate in `SlotAvailabilityChecker`
  (4 lines), strategy wiring in `ServiceNode` (8 lines), timeout/retry settings in `reservation.properties`
  (7 lines), constraint assertions in `LockingBenchmark.checkConstraints` (11 lines), and `pessimistic` → `fenced`.
* No import changes are needed for any typed edit: every file already imports with `package.*` where it uses
  2+ classes from one package, and `.idea/codeStyles/` keeps IntelliJ on the same rule.

## Pre-flight checklist (before you hit record)

1. IntelliJ: editor and terminal font 16+ pt. *Project Structure ▸ SDK* = **25** (any JDK 21+ works).
2. Chrome: open `https://github.com/Recorder20102026/recording-kit/tree/main/reference` in a tab. Keep
   `TRANSCRIPT.md` on a second screen or printed, **not** in the recorded Chrome window.
3. Do one silent dry run of `./scripts/test.sh` in the IntelliJ terminal so macOS doesn't pop permission dialogs.
4. Extract `input 1.zip` into a fresh folder for the take, so the baseline is pristine.
5. Reset mid-take: delete the project folder and re-extract the zip. Recover a broken edit with `solution.patch`.

## Expected results

| Step | Command | Result |
|---|---|---|
| 1.5 | `./scripts/test.sh` | **17 passed, 10 failed** (same 10 every run) |
| 2.4 | `./scripts/benchmark.sh` | `(current code)`: 10/10 race rounds double book, every burst round double books, p99 ≈ 120 ms |
| 3.3 | `./scripts/build.sh` after pasting | **1 error** in `ServiceNode.java:31` (constructor needs a `LockingStrategy`) |
| 3.7 | `./scripts/benchmark.sh pessimistic fenced` | `pessimistic` **REJECT** (~90 lock timeouts/round, ~1.7x amplification); `fenced` **ACCEPT** |
| 4.2 | `./scripts/test.sh burst timeout` (pessimistic) | 6 passed, 1 failed (`lockWaitAndRetryBudget`, amplification ~1.6x) |
| 4.5 | `./scripts/test.sh` + `./scripts/benchmark.sh` (fenced) | **27 passed, 0 failed**; `accepted=[fenced]` |

Verified 5 runs in a row: 27/27 and `accepted=[fenced] rejected=[pessimistic]` every time.

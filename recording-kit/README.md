# Recording kit: reservation-service locking investigation

| Path | What it is |
|---|---|
| `../input 1.zip` | Baseline project (bugs included). Extract it fresh before every take. |
| `../input 1/` | The same baseline, browsable on GitHub. |
| `../reference/` | **Reference GitHub patch** shown on camera: final version of all 14 changed files + `reference.patch` (see `reference/README.md`). |
| `TRANSCRIPT.md` | Step-by-step script: **DO** / **SAY** / **EXPECT**, with the four ✍️ edits you type by hand. |
| `expected-output/` | Real outputs from a dry run of every phase. |

## Copy-paste vs. typed

* **Copy-paste from `reference/` (Chrome → Copy raw file → IntelliJ ⌘V):** `LockingBenchmark` (with its
  constraint assertions), `benchmark.sh`, `SlotBucket`, `Schema`, `LockingStrategy`, `PessimisticResourceLock`,
  `SlotBucketFence`, `RetryPolicy`, `ReservationService`, `ReservationConfig`, `ADR-007`.
* **Typed on camera (✍️ T1–T4, exact text in `TRANSCRIPT.md`):** overlap predicate in `SlotAvailabilityChecker`
  (4 lines), strategy wiring in `ServiceNode` (8 lines), timeout/retry settings in `reservation.properties`
  (7 lines), and `pessimistic` → `fenced`. The final versions of these files are in `reference/` too, so you
  can check your typing against them, or paste them if a take goes wrong.
* No import changes are needed for any typed edit: every file already imports with `package.*` where it uses
  2+ classes from one package, and `.idea/codeStyles/` keeps IntelliJ on the same rule.

## Pre-flight checklist (before you hit record)

1. IntelliJ: editor and terminal font 16+ pt. *Project Structure ▸ SDK* = **25** (any JDK 21+ works).
2. Chrome: open `https://github.com/Recorder20102026/recording-kit/tree/main/reference` in a tab. Keep
   `TRANSCRIPT.md` on a second screen or printed, **not** in the recorded Chrome window.
3. Do one silent dry run of `./scripts/test.sh` in the IntelliJ terminal so macOS doesn't pop permission dialogs.
4. Extract `input 1.zip` into a fresh folder for the take, so the baseline is pristine.
5. Reset mid-take: delete the project folder and re-extract the zip. Recover a broken edit by pasting the file
   from `reference/`, or apply everything: `patch -p1 < ../reference/reference.patch && chmod +x scripts/benchmark.sh`.

## Expected results

| Step | Command | Result |
|---|---|---|
| 1.5 | `./scripts/test.sh` | **17 passed, 10 failed** (same 10 every run) |
| 2.4 | `./scripts/benchmark.sh` | `(current code)`: 10/10 race rounds double book, every burst round double books, p99 ≈ 100–125 ms; **REJECT** (double bookings) |
| 3.3 | `./scripts/build.sh` after pasting | **1 error** in `ServiceNode.java:31` (constructor needs a `LockingStrategy`) |
| 3.6 | `./scripts/benchmark.sh pessimistic fenced` | `pessimistic` **REJECT** (80–92 lock timeouts/round, ~1.6–1.7x amplification); `fenced` **ACCEPT** |
| 4.2 | `./scripts/test.sh burst timeout` (pessimistic) | 6 passed, 1 failed (`lockWaitAndRetryBudget`, amplification ~1.5–1.6x) |
| 4.5 | `./scripts/test.sh` + `./scripts/benchmark.sh` (fenced) | **27 passed, 0 failed**; `accepted=[fenced]` |

Verified on repeated runs: 27/27 and `accepted=[fenced] rejected=[pessimistic]` every time.

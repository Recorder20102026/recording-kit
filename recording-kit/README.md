# Recording kit: reservation-service locking investigation

| Path | What it is |
|---|---|
| `../input 1.zip` | Baseline project (bugs included). Extract it fresh before every take. |
| `../input 1/` | The same baseline, browsable on GitHub. |
| `../reference/` | **Reference GitHub patch** shown on camera: locking structures, `RetryPolicy`, `ReservationConfig`, `LockingBenchmark` skeleton (see `reference/README.md`). |
| `TRANSCRIPT.md` | Step-by-step script: **DO** (mouse actions) / **SAY** / **EXPECT**, with the ✍️ H1–H9 code you type by hand. |
| `expected-output/` | Real outputs from a dry run of every phase. |

## Pasted vs. typed

* **Pasted from `reference/`** (Chrome **Copy raw file** → IntelliJ right-click package ▸ **Paste**, or
  **Edit ▸ Select All** + **Edit ▸ Paste** to replace): `SlotBucket`, `Schema`, `LockingStrategy`,
  `PessimisticResourceLock`, `SlotBucketFence`, `ReservationService`, `RetryPolicy`, `ReservationConfig`,
  the `LockingBenchmark` skeleton (`main` only) and `ADR-007`.
* **Typed on camera** (✍️, exact text in `TRANSCRIPT.md`):
  * **Harness**, extending the existing testkit: `Fixtures.cluster(ReservationConfig, int)` (H1),
    `Workloads.OVERLAP_RACE` + `roundRobin` (H2), `RunReport.lockTimeoutsPerRequest` (H3), and in the pasted
    `LockingBenchmark` skeleton the race round `overlapRace`, the burst loop `burst` and `checkConstraints`
    (H4, ~40 lines), plus `benchmark.sh` copied from `test.sh` (H5).
  * **Integration and values**: overlap predicate (H6), `ServiceNode` strategy wiring (H7), retry / timeout
    values in `reservation.properties` (H8), and `pessimistic` → `fenced` (H9).
* Every typed step was replayed against the baseline and produces a project that builds and passes.

## Pre-flight checklist (before you hit record)

1. IntelliJ: editor and terminal font 16+ pt. *File ▸ Project Structure ▸ SDK* = **25** (any JDK 21+ works).
2. IntelliJ: *Settings ▸ Editor ▸ General ▸ Smart Keys*: untick *Insert paired brackets* and *Insert pair
   quote*, so typed code doesn't get doubled `)` `}` `"`.
3. Chrome: open `https://github.com/Recorder20102026/recording-kit/tree/main/reference` in a tab. Keep
   `TRANSCRIPT.md` on a second screen or printed, **not** in the recorded Chrome window.
4. Do one silent dry run of `./scripts/test.sh` in the IntelliJ terminal so macOS doesn't pop permission dialogs.
5. Extract `input 1.zip` into a fresh folder for the take, so the baseline is pristine.
6. Mid-take mistakes in typed code: right-click the file ▸ **Local History ▸ Show History** and revert to the
   entry before the edit, then type it again.

## Expected results

| Step | Command | Result |
|---|---|---|
| 1.5 | `./scripts/test.sh` | **17 passed, 10 failed** (same 10 every run) |
| 2.6 | `./scripts/benchmark.sh` | `configured` (baseline): race 10/10 rounds double book, every burst round double books; **REJECT, 4 check(s) failed** |
| 3.3 | `./scripts/build.sh` after pasting | **1 error** in `ServiceNode.java:31` (constructor needs a `LockingStrategy`) |
| 3.6 | `./scripts/benchmark.sh pessimistic fenced` | `pessimistic` **REJECT, 6 check(s) failed** (70–92 lock timeouts/round, 1.55–1.71x); `fenced` **ACCEPT** |
| 4.2 | `./scripts/test.sh burst timeout` (pessimistic) | 6 passed, 1 failed (`lockWaitAndRetryBudget`) |
| 4.5 | `./scripts/test.sh` + `./scripts/benchmark.sh` (fenced) | **27 passed, 0 failed**; `verdict: ACCEPT` |

Verified on 5 repeated runs: `accepted=[fenced]  rejected=[pessimistic]` every time.

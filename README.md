# reservation-service: locking investigation

| Path | What it is |
|---|---|
| `input 1.zip`, `input 1/` | Brownfield multi-pod reservation service (baseline; reproduces overlapping-interval double bookings). |
| `reference/` | Reference patch: candidate locking structures (`LockingStrategy`, pessimistic resource lock, fenced slot buckets), `ReservationService`, bounded `RetryPolicy`, `ReservationConfig`, `LockingBenchmark` skeleton, ADR template. |
| `recording-kit/` | Recording script (`TRANSCRIPT.md`) and expected outputs. |

The harness workloads and assertions, the retry / timeout values and the pod integration are written in the
service itself, on top of its existing `testkit` (see `recording-kit/TRANSCRIPT.md`).

Requirements: JDK 21+, no Maven or Gradle (`./scripts/build.sh`, `./scripts/test.sh`).

# reservation-service: locking investigation

| Path | What it is |
|---|---|
| `input 1.zip`, `input 1/` | Brownfield multi-pod reservation service (baseline; reproduces overlapping-interval double bookings). |
| `reference/` | Reference patch with the candidate locking structures: `LockingStrategy`, pessimistic resource lock, fenced slot buckets, `ReservationService`, ADR template. |
| `recording-kit/` | Recording script (`TRANSCRIPT.md`) and expected outputs. |

The race / 20x burst harness, the bounded retry wiring and the pod integration are written in the service
itself, on top of its existing `testkit` (see `recording-kit/TRANSCRIPT.md`).

Requirements: JDK 21+, no Maven or Gradle (`./scripts/build.sh`, `./scripts/test.sh`).

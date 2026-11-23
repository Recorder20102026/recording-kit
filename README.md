# reservation-service: locking investigation

| Path | What it is |
|---|---|
| `input 1.zip`, `input 1/` | Brownfield multi-pod reservation service (baseline; reproduces overlapping-interval double bookings). |
| `reference/` | Reference patch with every change: candidate locking structures (pessimistic resource lock, fenced slot buckets), bounded retry, service wiring and config, race/burst benchmark harness, ADR. |
| `recording-kit/` | Recording script (`TRANSCRIPT.md`) and expected outputs. |

Quick start (JDK 21+, no Maven or Gradle):

```bash
cd "input 1"
./scripts/test.sh                                  # baseline: 17 passed, 10 failed
patch -p1 < ../reference/reference.patch && chmod +x scripts/benchmark.sh
./scripts/benchmark.sh pessimistic fenced          # accepted=[fenced] rejected=[pessimistic]
./scripts/test.sh                                  # 27 passed, 0 failed
```

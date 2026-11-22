# Architecture notes

## Request path (create)

```
client ──► LoadBalancer (round robin) ──► ServiceNode (pod a | b | c)
                                             └─ ReservationController.postReservation
                                                  └─ ReservationService.createReservation
                                                       └─ Database.inTransaction("reservation.create", ...)
                                                            ├─ ResourceRepository / ReservationRepository  (SQL)
                                                            ├─ PaymentGateway.placeHold                (HTTP, external)
                                                            └─ EventBus.publish                         (broker, external)
```

Only `Database`, `PaymentGateway` and `EventBus` are shared between pods (see `cluster/Cluster.java`).
Anything a pod keeps in a field (a map, a lock, a cache) is invisible to the other pods.

## The database simulator (`db/`)

The tests run against an in-process model of the shared PostgreSQL primary so that they are
deterministic and need no infrastructure. It reproduces the semantics that matter for this incident:

| Simulator | PostgreSQL equivalent |
|---|---|
| `Transaction.select / selectWhere` | plain `SELECT` under READ COMMITTED: never blocks, sees committed rows + own writes |
| `Transaction.selectForUpdate` | `SELECT ... FOR UPDATE`. **Locks nothing if the row does not exist.** |
| `Transaction.insert` | `INSERT`. Takes the index-entry lock and raises `23505` on duplicate key. |
| `Transaction.update / updateIfVersion / updateWhereVersion` | `UPDATE ... [WHERE version = ?]` with a row lock |
| `Transaction.upsertAllForUpdate` | `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` (creates missing rows, locks all) |
| `upsertAllForUpdate(..., stopAfter)` | the same, evaluated row by row in a server-side function that stops early |
| `LockManager` | row-lock queue (FIFO); every wait bounded by `lock_timeout`, then `55P03` |
| `FaultInjector` | connection reset on COMMIT, lost COMMIT ack, stalled lock holder |
| `DbConfig.statementLatencyMicros` | one network round trip per statement |

Every statement costs one simulated round trip, and a COMMIT with writes costs a WAL flush. Lock
hold times are realistic, so lock waits, timeouts and retries can be measured.

## Side effects

`PaymentGateway` and `EventBus` are **not** part of any DB transaction. Once called, the effect
exists even if the transaction later rolls back. They de-duplicate on an idempotency key / event id.

`outbox/` implements the transactional outbox (RES-318): rows are written in the business
transaction and `OutboxRelay` delivers them after COMMIT with their idempotency keys.
`Cluster.quiesce()` runs the relay to completion (production runs it every 100ms).

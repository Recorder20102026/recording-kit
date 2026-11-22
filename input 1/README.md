# reservation-service

Booking API for courts and rooms. Customers reserve a resource for a half-open time interval
`[start, end)`; we place a card hold with the payment provider and publish a `RESERVATION_CREATED`
event that drives e-mails, calendar sync and billing.

Production runs **3+ stateless pods** behind a round-robin load balancer, all sharing one
PostgreSQL primary, one payment provider and one message broker.

> **Open incident:** [INC-2291](docs/INCIDENT-2291.md). Double bookings and orphaned card holds
> during the Saturday hot-slot release. Read that first.

## Requirements

* JDK 21+ (the project is configured for JDK 25 in IntelliJ IDEA)
* No build tool. Everything compiles with plain `javac` through the scripts below.

## Run

```bash
./scripts/test.sh                      # all suites
./scripts/test.sh overlap burst        # selected suites
./scripts/evaluate-designs.sh          # benchmark candidate locking designs (spikes)
./scripts/repro-race.sh                # one-shot reproduction of the cross-pod double booking
```

Suites: `overlap`, `burst`, `multinode`, `partial`, `timeout`, `regression`.

Every suite class (`src/test/java/.../suites/*Test.java`) and `RaceReproMain` also has a `main`
method, so you can Run/Debug it from the IntelliJ gutter. Use the project root as the working directory
(the IntelliJ default) so that `config/reservation.properties` is picked up.

## Layout

```
config/reservation.properties     runtime config (all pods read the same file)
docs/                             incident report, architecture notes
scripts/                          build / test / evaluation entry points
src/main/java/com/acme/reservations
  api/            HTTP adapter (ReservationController, HttpResponse)
  cluster/        Cluster = N ServiceNodes + shared Database / PaymentGateway / EventBus
  config/         ReservationConfig
  db/             in-process stand-in for the shared PostgreSQL primary (row locks, lock_timeout,
                  READ COMMITTED, faults); see docs/ARCHITECTURE.md for the SQL mapping
  domain/         TimeInterval, Reservation, Resource, request/result types
  outbox/         transactional outbox + relay (RES-318, used by the cancel path)
  persistence/    Schema + repositories
  service/        ReservationService (write path), SlotAvailabilityChecker, RetryPolicy
  sideeffects/    PaymentGateway, EventBus (external systems, not transactional)
src/test/java/com/acme/reservations
  suites/         the six test suites
  testkit/        zero-dependency runner, load generator, invariants, platform constraints
  evaluation/     DesignEvaluation + spikes of candidate locking designs
  debug/          RaceReproMain
```

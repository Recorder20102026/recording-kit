#!/usr/bin/env bash
# Race / burst benchmark for candidate locking strategies (LockingBenchmark).
#   ./scripts/benchmark.sh                        -> the strategy in config/reservation.properties
#   ./scripts/benchmark.sh pessimistic fenced     -> each candidate on the same workload
#   ROUNDS=5 ./scripts/benchmark.sh fenced        -> more burst rounds (default 3)
set -uo pipefail
cd "$(dirname "$0")/.."
./scripts/build.sh || exit 1
# Fixed heap + throughput GC: keeps GC pauses out of the lock-wait measurements.
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1g -XX:+UseParallelGC}"
java $JAVA_OPTS -cp build/classes:build/test-classes com.acme.reservations.testkit.LockingBenchmark "$@"

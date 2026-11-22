#!/usr/bin/env bash
# Benchmarks the candidate locking designs against the hard constraints.
#   ./scripts/evaluate-designs.sh        -> 3 rounds per design
#   ./scripts/evaluate-designs.sh 5      -> 5 rounds per design
set -uo pipefail
cd "$(dirname "$0")/.."
./scripts/build.sh || exit 1
# Fixed heap + throughput GC: keeps GC pauses out of the lock-wait measurements.
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1g -XX:+UseParallelGC}"
java $JAVA_OPTS -cp build/classes:build/test-classes com.acme.reservations.evaluation.DesignEvaluation "$@"

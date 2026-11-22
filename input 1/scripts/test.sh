#!/usr/bin/env bash
# Build and run test suites.
#   ./scripts/test.sh                      -> all suites
#   ./scripts/test.sh overlap burst        -> selected suites
# Suites: overlap | burst | multinode | partial | timeout | regression | all
set -uo pipefail
cd "$(dirname "$0")/.."
./scripts/build.sh || exit 1
# Fixed heap + throughput GC: keeps GC pauses out of the lock-wait measurements.
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1g -XX:+UseParallelGC}"
java $JAVA_OPTS -cp build/classes:build/test-classes com.acme.reservations.testkit.TestRunner "$@"

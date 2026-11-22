#!/usr/bin/env bash
# Reproduces the cross-pod partial-overlap double booking (INC-2291) once.
set -uo pipefail
cd "$(dirname "$0")/.."
./scripts/build.sh || exit 1
# Fixed heap + throughput GC: keeps GC pauses out of the lock-wait measurements.
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1g -XX:+UseParallelGC}"
java $JAVA_OPTS -cp build/classes:build/test-classes com.acme.reservations.debug.RaceReproMain

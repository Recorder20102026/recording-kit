#!/usr/bin/env bash
# Compiles main + test sources with plain javac (no Maven/Gradle needed).
set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes

find src/main/java -name '*.java' > build/main-sources.txt
javac --release 21 -Xlint:unchecked -d build/classes @build/main-sources.txt

find src/test/java -name '*.java' > build/test-sources.txt
if [ -s build/test-sources.txt ]; then
  javac --release 21 -cp build/classes -d build/test-classes @build/test-sources.txt
fi

echo "[build] OK  ($(wc -l < build/main-sources.txt | tr -d ' ') main / $(wc -l < build/test-sources.txt | tr -d ' ') test sources)"

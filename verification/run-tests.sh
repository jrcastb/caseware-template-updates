#!/usr/bin/env bash
# Compiles and runs the fan-out worker tests WITHOUT Maven (used where Maven/network are unavailable).
# The real build is `mvn test` in fanout-worker/; this script does not replace it.
#   run-tests.sh [source-root] [runs]      source-root defaults to ../fanout-worker
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$HERE/../fanout-worker}"
RUNS="${2:-1}"
OUT="$(mktemp -d)"
# javac is invoked through the jdk.compiler module so this works on JREs that ship the module but no javac binary.
JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
command -v javac >/dev/null 2>&1 && JAVAC=(javac)
"${JAVAC[@]}" -Xlint:all -Werror -d "$OUT/main" $(find "$SRC/src/main/java" -name '*.java')
"${JAVAC[@]}" -d "$OUT/standin" $(find "$HERE/junit-standin" -name '*.java')
"${JAVAC[@]}" -Xlint:all -Werror -cp "$OUT/main:$OUT/standin" -d "$OUT/test" $(find "$SRC/src/test/java" -name '*.java')
for i in $(seq 1 "$RUNS"); do
  java -cp "$OUT/main:$OUT/standin:$OUT/test" StandInRunner "$OUT/test" "${TEST_TIMEOUT:-30}" > "$OUT/run-$i.txt" || true
  echo "run $i: $(grep SUMMARY "$OUT/run-$i.txt")"
  grep '^FAIL' "$OUT/run-$i.txt" || true
done
[ "$RUNS" = 1 ] && cp "$OUT/run-1.txt" "${RESULT_FILE:-/dev/null}" 2>/dev/null || true
echo "classes: $OUT"

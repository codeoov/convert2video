#!/usr/bin/env bash
# Cursor stop hook — :app:compileDebugKotlin (macOS/Linux)
cat >/dev/null || true
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
if [[ ! -f "./gradlew" ]]; then
  echo '{}'
  exit 0
fi
chmod +x ./gradlew 2>/dev/null || true
log="$(mktemp -t happy-cursor-hook-compile.XXXXXX.log)"
./gradlew :app:compileDebugKotlin --no-daemon >"$log" 2>&1
code=$?
if [[ "$code" -ne 0 ]]; then
  echo "Gradle compileDebugKotlin failed (exit $code). Tail:" >&2
  tail -n 80 "$log" >&2 || true
fi
rm -f "$log"
echo '{}'
exit "$code"

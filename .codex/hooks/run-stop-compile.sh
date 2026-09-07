#!/usr/bin/env bash
# Claude Code Stop hook — :app:compileDebugKotlin (macOS/Linux)
cat >/dev/null || true
ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$ROOT" || exit 0
if [[ ! -f "./gradlew" ]]; then
  exit 0
fi
chmod +x ./gradlew 2>/dev/null || true
log="$(mktemp -t convert2video-claude-hook-compile.XXXXXX.log)"
./gradlew :app:compileDebugKotlin --no-daemon >"$log" 2>&1
code=$?
if [[ "$code" -ne 0 ]]; then
  echo "Gradle compileDebugKotlin failed (exit $code). Tail:" >&2
  tail -n 80 "$log" >&2 || true
  rm -f "$log"
  exit 2
fi
rm -f "$log"
exit 0

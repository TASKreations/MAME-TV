#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}/platform-tools/adb"
"$adb_bin" install --no-streaming --no-incremental -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" install --no-streaming --no-incremental -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
log=$(mktemp)
trap 'rm -f "$log"' EXIT
"$adb_bin" shell am instrument -w com.taskreations.mametv.test/com.taskreations.mametv.SmokeInstrumentation | tee "$log"
# adb can exit zero even when instrumentation fails. Verify the runner's result.
python3 - "$log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
if 'INSTRUMENTATION_RESULT: testsPassed=3' not in text or 'INSTRUMENTATION_CODE: -1' not in text:
    raise SystemExit('Device smoke checks did not all pass')
PY

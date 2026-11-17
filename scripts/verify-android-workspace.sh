#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$project_dir"
# Optional machine-local setup; deliberately excluded from Git.
if [[ -f .gradle-local/environment.sh ]]; then
    source .gradle-local/environment.sh
fi
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or 21}"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK Platform 35}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
test -x "$ANDROID_HOME/build-tools/35.0.0/apksigner"
java -version
gradle_args=()
if [[ -n ${RELAY_GRADLE_INIT:-} ]]; then
    test -f "$RELAY_GRADLE_INIT"
    gradle_args+=(-I "$RELAY_GRADLE_INIT")
fi
./gradlew "${gradle_args[@]}" :app:testDebugUnitTest :app:assembleDebug "$@"
python3 scripts/verify-recovery.py
python3 scripts/check-call-apk.py app/build/outputs/apk/debug/app-debug.apk
if [[ -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    "$ANDROID_HOME/platform-tools/adb" devices -l
else
    printf '%s\n' 'ADB is not installed; physical-device testing is unavailable.'
fi
printf '%s\n' 'Build and automated checks complete. An attached device and actual WebView rendering checks are still required to verify the shared-app black-screen fix.'

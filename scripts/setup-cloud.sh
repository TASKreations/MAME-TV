#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tools=/workspace/toolchains
mkdir -p "$tools"
export JAVA_HOME="$tools/jdk-21.0.9+10"
export ANDROID_HOME="$tools/android-sdk"

if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    archive=OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz
    curl -fL --retry 2 "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/$archive" -o "$tools/$archive"
    printf '%s  %s\n' '810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f' "$tools/$archive" | sha256sum -c -
    tar -xzf "$tools/$archive" -C "$tools"
fi
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    archive="$tools/cmdline-tools.zip"
    curl -fL --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip -o "$archive"
    printf '%s  %s\n' 'e025545c62a8e64c7559119566a569fb1dec5f60' "$archive" | sha1sum -c -
    staging=$(mktemp -d "$tools/sdk-tools.XXXXXX")
    unzip -q "$archive" -d "$staging"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    mv "$staging/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rmdir "$staging"
fi
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# yes may receive SIGPIPE after sdkmanager exits; retain the installer's exit status.
set +o pipefail
yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses > "$tools/sdk-licenses.log" 2>&1
license_status=${PIPESTATUS[1]}
set -o pipefail
if [[ "$license_status" != 0 ]]; then cat "$tools/sdk-licenses.log"; exit "$license_status"; fi
"$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'platform-tools'
bash scripts/prepare-core.sh
bash scripts/gradle-cloud.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

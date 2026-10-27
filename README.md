# MAME-TV

An unofficial, noncommercial Android TV arcade app by TASKreations. Native Android
screens manage your ROM library; **MAME2003-Plus runs inside the app**, through a
small libretro/JNI frontend. No separate emulator app is needed and no ROMs are
bundled.

## First version

- TV launcher entry and D-pad focus navigation, with a remote-friendly library.
- Built-in core with software video rendering, stereo audio, and one gamepad.
- Import ROM ZIPs through Android's document picker, including USB providers.
- Download ROM ZIPs from HTTPS URLs, plus a Gridlee shortcut to MAME's official
  authorized ROM site and a link to its other authorized downloads.
- ZIPs are saved intact under `files/roms/mame2003-plus/`. The app rejects invalid
  archives, traversal filenames, duplicate names, downloads over 512 MB, and
  redirects to cleartext HTTP. Interrupted imports do not appear in the library.
- The core's save/NVRAM directory is `files/saves/`; its system directory is
  `files/system/`. All paths are app-private; uninstalling clears them.

This is an initial implementation. Read the validation record below before
assuming a game or controller has been tested. Downloads run while the library
activity lives; this version does not offer background download/resume support.

## ROM compatibility and downloads

Use **MAME2003-Plus ROM sets**, based on MAME 0.78 with additional backported drivers.
Modern MAME sets are not interchangeable. Preserve the original ZIP short name
(such as `gridlee.zip`). Import any required parent/BIOS ZIPs alongside the game;
the core looks up those dependencies. BIOS files will also appear in the ROM list
and are not standalone playable games. ZIP validity does not establish that a
game's ROM contents match its driver. Failed loads display the core's error.

Only download games you are legally entitled to use. The official authorized
catalog is <https://www.mamedev.org/roms/>; individual games have their own terms.
The app does not index unauthorized ROM sites or bundle commercial games. The
catalog link needs a browser installed on the TV; direct URL downloads work in
the app. Android TV builds without a document picker need an installed document
provider for local/USB import.

## Controls

| Input | Action |
| --- | --- |
| D-pad or left stick | Movement / native startup notices |
| A, B, X, Y, L1, R1, L2, R2 | Core's default arcade buttons |
| Select | Insert coin |
| Start | Start game |
| Back | Exit to library |
| TV remote Menu / Play-Pause / OK | Coin / Start / action |

A Bluetooth or USB gamepad is recommended. Keep the original MAME startup notices;
some require Left then Right to continue. This version supports one player with
digital input. Analog trackball, light guns, per-game remapping, CHD import, and a
save-state UI remain future work. Availability of remote keys varies by TV.

## Build

Requirements: Linux/macOS/Windows with a full JDK 17–21, Android SDK platform 36,
build-tools 36.0.0, NDK 28.2.13676358, `curl`, `tar`, and `sha256sum` (or use Linux).
Gradle 8.13 and Android Gradle Plugin 8.13.2 are pinned. ARM64, ARMv7, and x86-64
libraries are compiled; the first build is substantially slower than incremental
builds because it compiles the full arcade core for all three architectures.

```bash
export ANDROID_HOME=/path/to/android-sdk
bash scripts/prepare-core.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`prepare-core.sh` downloads the exact upstream source revision
`ca185b19ad27d531a91c92fa456aabd951004fb8`, verifies its SHA-256, and extracts it
to ignored `.native/mame2003-plus/`. It does not modify upstream sources. Native
compilation uses three jobs to fit typical cloud machines. Do not delete `.native`
or native build outputs between tasks unless a clean build is needed.

For this managed cloud environment:

```bash
bash scripts/setup-cloud.sh
# Subsequent builds:
bash scripts/gradle-cloud.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Cloud setup installs a checksum-verified JDK and SDK under `/workspace/toolchains`,
preserves TLS verification, and forwards the configured proxy and system CA trust
to Gradle. It does not require credentials in repository files.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install on your TV
using `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then launch
MAME-TV from the TV app list. Enable developer mode and pair/authorize ADB on the
TV first. Do not commit signing keys; the debug APK is for development.

For storage, TV-focus, and native invalid-ROM smoke checks on an attached device:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash scripts/device-smoke.sh
```

Select the device with `ANDROID_SERIAL` if more than one is connected. The custom
runner requires all three checks to pass; the script checks its result rather
than relying on ADB's exit code. It uses a synthetic ZIP with no game data and does
**not** validate actual game video, audio, or controller mapping. On cloud, use
`scripts/gradle-cloud.sh` instead of invoking Gradle directly.

Each cloud task already has an isolated checkout. Use `/workspace/MAME-TV`;
do not create a Git worktree unless explicitly requested. No app server needs to
run to edit or build this native app. Emulator processes must be restarted after
restoring an environment snapshot.

## Licensing and attribution

The embedded MAME2003-Plus core uses the classic **MAME 0.78 noncommercial license**
and additional per-file notices. It cannot be used in a commercial product without
the applicable authors' authorization. Distribution requires freely available
source, preservation of notices, and compliance with all upstream terms. This
project is an unofficial derivative, not an official MAME distribution.

- [Exact core source and notices](https://github.com/libretro/mame2003-plus-libretro/tree/ca185b19ad27d531a91c92fa456aabd951004fb8)
- [Core license, included in the APK](app/src/main/assets/MAME2003-PLUS-LICENSE.md)
- [Frontend source](https://github.com/TASKreations/MAME-TV)

The Gradle wrapper is generated by Gradle and retains its own notices. New frontend
code is offered under the MIT license in `LICENSE`; that grant does not override
the embedded core's restrictions or grant any ROM rights. Commercial distribution
would need a separate licensing/core decision before release.

## Cloud validation

- The debug APK builds for ARM64, ARMv7, and x86-64 with both native libraries
  and license assets packaged. The compiled core reports the pinned revision.
- All seven local unit tests pass, covering archive preservation, duplicates,
  invalid/empty archives, interrupted reads, filenames, and HTTPS URL validation.
- Android lint passes with warnings for fixed TV orientation, backup policy,
  and English text localization. The upstream legacy core also emits compiler
  warnings; its source has not been altered to hide them.
- `scripts/setup-cloud.sh` was executed successfully and replayed against the
  retained dependencies. A device smoke runner is provided separately.
- **Actual gameplay has not been validated.** The managed runtime's current
  network policy blocks `www.mamedev.org`, so its authorized sample ROM could
  not be downloaded. Required domain additions were saved to the environment
  draft; saving a draft does not apply that policy or publish the environment.
  Game video, audio, controller mapping, and successful ROM downloads still need
  a compatible authorized set and a working Android device/emulator.

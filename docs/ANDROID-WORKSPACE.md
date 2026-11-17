# Android workspace verification

On November 17, 2026 the executor became reachable again. The source checkout, JDK 21, Android SDK Platform 35, build-tools 35.0.0 and Gradle dependency cache were intact. Installed official Android platform-tools 37.0.3 after verifying its archive checksum against the Android repository metadata. ADB starts successfully; no devices are attached.

A fresh run of :app:testDebugUnitTest :app:assembleDebug with --rerun-tasks succeeded (48 tasks executed). The APK WebRTC packaging check and recovery manifest verification passed. The saved PKCS12 key can be opened and its certificate matches the persistent RC58 identity recorded in RECOVERY.md. No key rotation is needed.

## Continue in the current workspace

Checkout: /workspace/scratch/ac158501f7e8/relay-lab
Machine-local configuration: .gradle-local/environment.sh (ignored by Git; contains tool paths only, no signing password). local.properties points to the SDK.

Run `bash scripts/verify-android-workspace.sh --offline` to use the existing cached dependencies. The script reads the optional machine-local environment, builds the debug APK, runs automated tests and recovery/packaging checks, and lists attached devices.

On a fresh machine, set JAVA_HOME to JDK 17 or 21 and ANDROID_HOME to an SDK with Platform 35 and build-tools 35.0.0. Run the same script without --offline to resolve dependencies. RELAY_GRADLE_INIT optionally selects an environment-specific repository init script. Do not assume these temporary workspace paths exist in a new session; source and signing recovery instructions remain in RECOVERY.md.

## Runtime testing is separate

This host is x86_64, has no /dev/kvm and no attached phone. Android Emulator 37.2.13 and an API 35 Google APIs image are now installed, but the software-mode boot did not complete within the bounded test attempt. See [EMULATOR-TEST-STATUS.md](EMULATOR-TEST-STATUS.md) for evidence and reproduction scripts. The distributed app packages ARM64 native libraries. ADB availability and Robolectric tests do not establish real Android WebView rendering. The shared-app black-screen report remains unresolved; see RC58-WEBXDC-BLACK-SCREEN.md. Do not label it fixed without a demonstrated correction and runtime verification.

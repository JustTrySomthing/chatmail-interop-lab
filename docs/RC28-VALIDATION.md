# rc28 validation — 14 November 2026

- Full JVM/Robolectric suite: **95 tests, 0 failures, 0 errors**, across 25 suites.
- Checks cover invite parsing/redemption, encrypted recovery, transport/delivery rules, attachment/media handling, settings/navigation, compact/landscape layout behavior, search/mute/pin, composer interaction, update selection and update prompts, local notifications and background scheduling.
- Added regression checks exclude recipient voicemail and locally deleted items from conversation feeds, validate all 18 accent/text combinations at WCAG 4.5:1 or better, verify default appearance and overlay bounds, and recognize the `ARM64-Preview.apk` release filename.
- Large-font bubble layouts rendered and inspected in light and dark modes with Robolectric native graphics. This is host UI rendering, not physical-device testing.
- Speech archive: all 13 files checked against their manifest hashes; voicebank is 819,200 bytes (two 409,600-byte rows). Runtime mapping is Female=0, Male=1, with Male selected by default.
- Retained WebRTC classes through R8 rules to protect JNI startup. Kept the recovered rc27 native binaries and generated release mapping for future crash diagnosis.

- Final APK: `apksigner verify` passes (RSA-3072, APK Signature Scheme v2); `zipalign -c 4` passes. Package `app.p2p.preview`, versionCode 64, versionName 0.7.23-rc28, minSdk 31, targetSdk 35.
- Release certificate SHA-256: `39e8ffec3e664cfd6b3865aa68e8ee43f9707fc8d7bbb14248a363f093314d13`.

## Limits

No physical Android phones were connected to the build environment. This release has not been validated here for a real two-phone call, microphone/speaker/Bluetooth routing, mobile-carrier TURN connectivity, Android 15/16 background delivery, or OEM battery restrictions. JVM tests cannot prove those native/device behaviors. Install the same rc28 build on both phones for the next device check.

This is a reconstruction from surviving source and the released rc27 runtime/assets, plus the requested changes; the lost rc27 Kotlin workspace and old signing key were not recovered. One clean install is required. Future updates must use the privately backed-up rc28 signing key.

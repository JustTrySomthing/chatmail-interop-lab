# Relay Lab recovery entry point

Updated November 17, 2026.

## Current source
Repository JustTrySomthing/chatmail-interop-lab.
Active development branch securejoin-invites, draft PR #1.
Latest continuity checkpoint 360efccdc692e71084db483eb07f4e4c2a8699a7.
Fetch the current branch head before editing. Read its AGENTS.md and docs/CONTINUE-HERE.md.
Main's application source and RC53 download pointer are older than the development branch.

## Releases
RC56, version code 92, is the latest published release.
RC57, version code 93, is an unpublished GitHub draft containing all recovered updates.
RC57 debug and preview builds, 171 automated tests, preview vital lint and WebRTC packaging passed.
Prepared RC57 APK SHA-256 c8aa0515fc001811701a2f149e8743bc29b0e23fe9ee1c2fee985f783ccea7f6.
The draft APK has a different signing identity and cannot update installed RC56.

## Signing recovery
Original RC56 certificate SHA-256
8f901d6be86ba90c7df654c43d42932758ac9f5d33925e1492dfcb94c3ac7ece

Recovered workspace certificate SHA-256
63a142dc1d9576493b560238328fc329d4d9042a91967df4be72af63b28110c1

Original private key backup location is UNRESOLVED. This is incomplete recovery.
Searches covered the available local filesystem, prior conversation retrieval, saved-file title searches, and all Relay Lab release asset names. No matching original key backup has been located.
The public certificate and APK cannot replace the private key.
Do not generate a replacement key and present it as an in-place update.

When recovered, record the exact persistent backup identifier here, restore the saved copy independently, and verify an APK signed with it against the RC56 certificate before marking recovery complete. Keep key material and credentials in private persistent storage outside GitHub.

## Build
Use JDK 17 or 21 and Android SDK Platform 35.
Run ./gradlew :app:testDebugUnitTest :app:assembleDebug.
For preview run ./gradlew :app:assemblePreview.
Run python3 scripts/verify-recovery.py and python3 scripts/check-call-apk.py on the APK.
Run python3 scripts/check-release-signature.py APK --apksigner /path/to/sdk/build-tools/35.0.0/apksigner before publication.

Keep the original installation and its native Chatmail history during signing recovery. Record physical device tests separately from build checks.

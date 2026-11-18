# Relay Lab recovery entry point

Updated November 17, 2026. This GitHub repository is **public**.

## Current source

Repository: JustTrySomthing/chatmail-interop-lab. Active development: **securejoin-invites**, draft PR #1. Fetch its current head before editing. Main retains older application source; follow this pointer and read the branch README and docs/CONTINUE-HERE.md. Current diagnostic release source is tagged v0.7.23-rc67.

RC67 (103) fixes the bottom navigation label width so Channels is fully visible, balances the Groups and Channels icon sizes, and uses stars consistently for Saved across the toolbar, messages, and channel posts. Navigation destinations, unread indicators, saving behavior, and existing data are preserved. Includes the RC66 default-on daily update checks and release candidates, and the established RC58 signing identity.

## Persistent signing identity

The user authorized a new key for RC58 after searches could not recover the RC56 key. RC58 cannot update RC56 in place. Export a standard Chatmail backup before uninstalling the old app, then install RC58 and restore. Never delete the old app without preserving its history.

RC58 certificate SHA-256: `0b569d33543efb55c13c87bff6041944118dfff00c9a350e96776712e22347b0`.
Alias: `p2p`; keystore type: PKCS12; filename: `relay-lab.p12`.

Encrypted GitHub backup: `recovery/relay-lab-signing.enc.base64`.
Saved recovery file: `Relay-Lab-PRIVATE-Signing-Recovery.zip`.
Exact saved file ID: `libfile_fde3_f-7651393cd7ac819180919f480e191ff7_94ce12f584588191ad0c7e8558734059`.
The saved ZIP contains `relay-lab.p12` and `password.txt`. Restore this exact saved file in a fresh directory; do not depend on any workspace path. Its password also decrypts the GitHub copy. Neither the password nor unencrypted key belongs in the public repo.

To independently restore the GitHub copy, using password.txt from the saved recovery file:

```sh
base64 --decode recovery/relay-lab-signing.enc.base64 > signing.enc
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in signing.enc -out signing-recovery.zip -pass file:/secure/path/password.txt
```

The saved ZIP was restored in a separate directory and the downloaded GitHub backup was decrypted and matched byte-for-byte on November 17, 2026. The final RC58 APK was then signed with that restored key and passed the signature guard. Preserve this same identity for every subsequent release.

Historical RC56 certificate: `8f901d6be86ba90c7df654c43d42932758ac9f5d33925e1492dfcb94c3ac7ece`. Its key remains unavailable; public certificates/APKs cannot replace a signing key.

## Reproducible build

Use JDK 17 or 21 and Android SDK Platform 35. Set ANDROID_HOME. Restore the signing ZIP outside the checkout, then:

```sh
export P2P_INTEROP_KEYSTORE=/secure/path/relay-lab.p12
export P2P_INTEROP_KEY_PASSWORD="$(cat /secure/path/password.txt)"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assemblePreview
python3 scripts/verify-recovery.py
python3 scripts/check-call-apk.py app/build/outputs/apk/preview/app-preview.apk
python3 scripts/check-release-signature.py app/build/outputs/apk/preview/app-preview.apk --apksigner "$ANDROID_HOME/build-tools/35.0.0/apksigner"
```

Preview output: app/build/outputs/apk/preview/app-preview.apk. Publish the current build as P2P-0.7.23-rc67-ARM64-Preview.apk. Compare its SHA-256 with the uploaded asset. Update README, continuation notes and recovery manifest for each release. Record phone tests separately from build checks.

# Relay Lab recovery entry point

Updated November 18, 2026. This GitHub repository is **public**.

## Current source

Repository: JustTrySomthing/chatmail-interop-lab. Active development: **securejoin-invites**, draft PR #1. Fetch its current head before editing. Main retains older application source; follow this pointer and read the branch README and docs/CONTINUE-HERE.md. Current diagnostic release source is tagged v0.7.23-rc72.

RC72 (108) redesigns Saved with themed cards, photo/video previews, attachment filters, search, source labels and compact item actions. Preserves conversation Media, two-column Settings, and the established RC58 signing identity.

## Persistent signing identity

The user authorized a new key for RC58 after searches could not recover the RC56 key. RC58 cannot update RC56 in place. Export a standard Chatmail backup before uninstalling the old app, then install RC58 and restore. Never delete the old app without preserving its history.

RC58 certificate SHA-256: `0b569d33543efb55c13c87bff6041944118dfff00c9a350e96776712e22347b0`.
Alias: `p2p`; keystore type: PKCS12; filename: `relay-lab.p12`.

Use the owner's securely saved signing recovery material. Restore it outside the checkout. Do not publish the key, password, or account-specific recovery-file identifiers. Verify the restored certificate against the fingerprint above before signing.

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

Preview output: app/build/outputs/apk/preview/app-preview.apk. Publish the current build as P2P-0.7.23-rc72-ARM64-Preview.apk. Compare its SHA-256 with the uploaded asset. Update README, continuation notes and recovery manifest for each release. Record phone tests separately from build checks.

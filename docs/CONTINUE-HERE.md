# Continue Relay Lab development

## Current checkpoint

This repository is public. Read RECOVERY.md for exact source and signing recovery instructions. RC58 (94) includes all recovered changes on securejoin-invites, draft PR #1. Main has older application source; use the development branch or RC58 tag.

The recovered Webxdc checkpoint is ed5f9ed4e56106d464ed93ed13cd4cce53998bbc. It includes final source and tests recovered from the prior conversation workspace. No additional source differences remained in that workspace before version preparation.

## Last work and user requests

The user requested another bug review after reporting blank Webxdc pages and seeing only one app after adding a second. Recent work fixes startup, lists shared app instances, supports switching and home-screen shortcuts, revalidates stale sessions, and reloads motion-enabled apps with fresh startup status.

The final recovered edits open the owning conversation when switching between apps across chats. They prevent a second file chooser from replacing a pending chooser and return all selected content URIs. The automated test covers single and multiple files, duplicate removal, cancellation, and unsupported URI schemes.

The user also requested attachment behavior matching Delta Chat and ArcaneChat. Current implementation uses the upstream Android 1 GiB local ceiling and upstream media preparation settings. Delivery remains subject to relay limits. Recent attachment commits address validation, retries, canceled copies, deletion, and cleanup of incomplete saves.

## Verification of the recovered checkpoint

Executed from a fresh Git checkout with the recovered edits, JDK 17, and Android SDK Platform 35.

- Gradle tasks :app:testDebugUnitTest and :app:assembleDebug passed.
- 171 tests across 31 suites passed, with zero failures, errors, or skipped tests.
- scripts/verify-recovery.py verified all 187 checkpoint files.
- scripts/check-call-apk.py passed for the new debug APK.
- APK signature verification passed using the existing local Android debug identity.
- This is a debug verification build. A new release containing the Webxdc fixes has yet to be published.

The environment used an existing dependency cache and a local Maven routing init script with Gradle offline mode. These are workspace build aids. Standard instructions remain in README.

## Next work

Verify Webxdc blank-page recovery, separate shared app instances, cross-chat switching, file picking and launcher shortcuts on a phone. Record phone verification separately from automated results.

The branch README records user-reported successful Delta Chat calls in both directions on November 16. Exact client versions and networks were not recorded. Other cross-client checks remain pending where indicated in README.

## RC58 signing transition

User authorized a new persistent key after RC56 key recovery failed. RC58 cannot update RC56 in place; export Chatmail history before reinstalling. RC57 remains an unpublished draft. RC58 uses the dedicated signing configuration, with encrypted backup in GitHub and an independently saved recovery ZIP. RECOVERY.md contains the exact saved-file ID, certificate, restore and build commands. Never generate another key for routine builds.

## RC58 build verification, November 17, 2026

171 tests passed with zero failures/errors/skips. Debug and preview assembly and preview vital lint passed. Final APK package app.p2p.interop, version code 94, version 0.7.23-rc58. WebRTC packaging and signature guard passed. The distribution APK was signed using the independently restored saved key. SHA-256: d749d308c25277dfdf2b3974d0e6ff5375545b6c36f73a4b4f56efbe7f076ab2.

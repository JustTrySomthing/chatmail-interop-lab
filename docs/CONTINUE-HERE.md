# Continue Relay Lab development

## Recovered on November 17, 2026

- Repository is JustTrySomthing/chatmail-interop-lab.
- Read main and its README first. Main currently points to 144dacaeb74c35c2c1ce0696b145055384dd5258 and links RC53.
- Latest published release candidate is v0.7.23-rc56, version code 92. The securejoin-invites branch README links that release.
- Ongoing implementation is in draft PR #1, on securejoin-invites. It has changes newer than RC56. Review that branch before continuing or publishing.
- The recovered Webxdc checkpoint is ed5f9ed4e56106d464ed93ed13cd4cce53998bbc. It includes source and a regression test recovered from the prior workspace after the conversation named Relay Lab development update reached its limit.
- Keep experimental work here. Relay Lab uses app.p2p.interop, its own update feed and signing identity, and fresh test profiles.

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

1. Continue the Webxdc review from the recovered checkpoint. Verify blank-page recovery, separate shared instances, cross-chat switching, file picking, and Android launcher shortcuts on a phone.
2. Prepare an incremented preview release containing the post-RC56 fixes when requested, using the existing Relay Lab signing identity for in-place updates. Confirm package, version code, certificate, APK digest, and update feed before release.
3. Reconcile main's older release link and draft PR state during the intended release or merge.
4. Record phone verification separately from build and automated results.

The branch README records user-reported successful Delta Chat calls in both directions on November 16. Exact client versions, network type, and individual video checks were not recorded. SecureJoin, text, channels, ArcaneChat calls, and Webxdc runtime and multiplayer checks remain pending where marked in the README.

## Recovery and build

Use the current GitHub branches, README, RECOVERY-FILES.json, and scripts/verify-recovery.py to recover source. Build with JDK 17 or 21 and Android SDK Platform 35.

Run ./gradlew :app:testDebugUnitTest :app:assembleDebug. Debug output is app/build/outputs/apk/debug/app-debug.apk.

Release signing uses P2P_INTEROP_KEYSTORE and P2P_INTEROP_KEY_PASSWORD. Recover signing material through the established private environment. Keep credentials outside source, releases, and ChatGPT project attachments.

## RC57 preparation on November 17, 2026

The user requested a new APK containing every update from the prior conversation and publication on GitHub. Version 0.7.23-rc57, code 93, includes all source through ed5f9ed and its predecessors. Comparison with the recovered prior workspace found no additional source differences before version preparation.

Publication is blocked on recovery of the established preview signing key. The downloaded RC56 APK has signer SHA-256 8f901d6be86ba90c7df654c43d42932758ac9f5d33925e1492dfcb94c3ac7ece. The only recovered local Android debug key has signer SHA-256 63a142dc1d9576493b560238328fc329d4d9042a91967df4be72af63b28110c1. The private key for RC56 was unavailable in this workspace, repository, and available signing-file inventory. The prepared preview therefore requires signing again with the RC56 key before publication as an in-place update.

RC56 remains the published version until signing continuity is restored. Recover the original Relay Lab signing key, compare its public certificate with RC56, sign RC57, and publish with the versioned APK name from README. Keep native Chatmail history on the existing installation during recovery.

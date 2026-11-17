# Relay Lab continuity and publishing

This repository is public. Read RECOVERY.md on main, fetch the current development branch, then read its README and docs/CONTINUE-HERE.md. Preserve uncommitted work before changing checkouts.

Keep all Relay Lab experiments and releases here, separate from P2P. Package app.p2p.interop has its own data, update feed, and signing identity. Keep source, native runtime assets, Gradle wrapper, dependency versions, tests and progress checkpoints committed. Update main recovery pointers at each release or branch change.

RC58 establishes a new persistent signing identity, explicitly authorized after the RC56 key could not be recovered. Do not claim RC56 can update in place. Do not generate another key for a routine build. Restore the saved key using RECOVERY.md; verify the restored copy by signing and checking an APK. Keep unencrypted key material and passwords out of this public repository. The encrypted backup belongs in recovery/.

Before publishing run the required Gradle tests/build, recovery manifest check, APK packaging check and scripts/check-release-signature.py. Check package and increasing version code; compare uploaded asset digest with the local APK. Record device-tested behavior separately from automated checks. Push completed work and document unfinished work before ending a session.

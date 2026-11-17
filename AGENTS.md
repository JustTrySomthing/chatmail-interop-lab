# Relay Lab continuity and publishing

Read RECOVERY.md on main at the start of each continuation. Follow its current development branch and then read docs/CONTINUE-HERE.md there. Fetch the remote before editing. Preserve uncommitted work before changing checkouts.

The user has authorized maintaining recovery information alongside development. Keep source, native runtime assets, Gradle wrapper, exact dependency versions, tests, and meaningful progress checkpoints in this repository. Commit and push authorized completed changes before ending a development session. Record unfinished edits and the exact branch and commit. Update the recovery pointer on main when the working branch or recovery state changes.

A successful build alone does not establish signing recovery. Before publishing an APK, run scripts/check-release-signature.py with the SDK apksigner and the actual APK. Compare package and version code to the preceding release. Verify the uploaded asset digest.

The original RC56 private signing key is currently missing. The local debug key recovered in November 2026 has a different certificate. Never describe that key as the recovered release identity. A matching private key must be restored and exercised by signing a test APK before claiming signing recovery is complete.

When the original key is recovered, save it with its required credentials in private persistent storage outside this repository, record the exact persistent file ID and certificate fingerprint in the recovery note, then restore that saved copy into a new temporary directory and verify it can sign an APK matching RC56. Do not depend on a temporary workspace path. Keep private signing material out of source and release assets.

Document build checks and physical-device checks separately. New source and new build verification remain possible while signing recovery is blocked.

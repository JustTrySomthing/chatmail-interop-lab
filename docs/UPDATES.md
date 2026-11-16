# P2P updates

When daily checks are enabled, P2P checks GitHub once per 24 hours and again when the app is opened after a phone restart. Android may defer a scheduled check while the app is closed. Failed automatic checks back off for at least an hour. An available update can appear from Chats, Groups, Social, or Files. If another dialog is open, the update prompt waits for it to close.

**Settings → Check for updates** checks on demand and shows the installed version, last successful check, and controls for daily checks and release candidates. Daily checks are on by default. Choose **Download update** to download and verify an APK. When the download is ready, choose **Install update**. Android asks for install permission when needed and confirms the in-place update. A verified download can be installed after the app restarts. Update alerts are silent during Quiet Hours.

## Release feed

The app checks public releases in `JustTrySomthing/P2P-Releases`. This release-only repository is separate from the private `JustTrySomthing/P2P` repo. Publishing an APK there makes that APK publicly downloadable. Public releases require no credentials on users’ phones. The first build containing the updater must be installed manually.

- Use a tag such as `v0.6.1` or `v0.6.1-rc1` and a matching Android version name. Increase the Android version code.
- Attach `P2P-0.6.1.apk` for a universal APK, `P2P-0.6.1-arm64.apk` for ARM64, or `P2P-0.6.1-x86_64.apk` for x86-64. For a release candidate, include `-rc1` in the version in the file name.
- For the separate P2P Test app, attach `P2P-<version>-ARM64-Test.apk` to a prerelease. Mark regular app release candidates as prereleases as well. Release candidates are included by default. Users can turn them off in Settings → Check for updates.
- Retain the application ID and the installed signing certificate. The Android installer must accept the update over the installed build. A build signed with a different key cannot replace it through this updater.
- Use GitHub's SHA-256 release asset digest. P2P checks the download size and digest, the package ID, version, minimum Android version, and exact signer before handing the APK to Android. The installer receives a read-only grant for the verified APK.

Test both stable and prerelease selection, network changes, first opening after reboot, and in-place installation on a physical Android device.

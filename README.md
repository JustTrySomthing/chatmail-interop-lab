# Relay Lab

Relay Lab is a Chatmail-based Android messenger built to work with Delta Chat and ArcaneChat. It offers encrypted chats and groups, channels, voice and video calls, chat-shared Webxdc apps, and offline read-aloud.

## Download

| Build | Android APK |
| --- | --- |
| **Latest release candidate** | [**Download Relay Lab 0.7.23-rc53**](https://github.com/JustTrySomthing/chatmail-interop-lab/releases/download/v0.7.23-rc53/P2P-0.7.23-rc53-ARM64-Preview.apk) · ARM64 · Android 12+ |
| **Final release** | A final release has yet to be published. |

RC means release candidate. **RC53 is the current build to install.** For release notes and older builds, see [Releases](https://github.com/JustTrySomthing/chatmail-interop-lab/releases).

### Update from the app

Open **Settings → Updates**, enable **Include release candidates**, and tap **Check now**. RC53 is offered to ARM64 devices running an older Relay Lab build. You can turn on automatic checks in the same menu.

## Features

- **Chats and groups** with SecureJoin QR and link invites, encrypted attachments, replies, reactions, and group descriptions.
- **Webxdc apps** with a searchable catalog of tools and games that can be shared in Chatmail chats and groups.
- **Channels** for publishing posts and following broadcasts, with QR and link invites.
- **Talk** with voice and video calls and in-call camera switching.
- **Media and accessibility** with inline audio, link previews, media trimming and compression, and offline read-aloud.

Relay Lab uses Chatmail Core for interoperability. Cross-app support varies by client version and feature. See [device test status](https://github.com/JustTrySomthing/chatmail-interop-lab/tree/v0.7.23-rc53#device-test-status).

## Build

Use the [RC53 release tag](https://github.com/JustTrySomthing/chatmail-interop-lab/tree/v0.7.23-rc53) for the source of the downloadable APK. Build with JDK 17 or 21 and Android SDK Platform 35. Set `ANDROID_HOME` and run `./gradlew :app:testDebugUnitTest :app:assembleDebug`.

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Relay Lab uses package `app.p2p.interop`. For separately signed builds, configure `P2P_INTEROP_KEYSTORE` and `P2P_INTEROP_KEY_PASSWORD`.

### Release publishing

Update the applicable download row after publishing each build. For release candidates, use tag `vX.Y.Z-rcN`, mark the release as a pre-release, and upload `P2P-X.Y.Z-rcN-ARM64-Preview.apk`. Verify the APK's SHA-256 digest in the public release feed. In-place updates require the same app package and signing certificate as the installed build, and a higher version code.

The current in-app updater selects release candidates. Verify the RC-to-final upgrade path before publishing a final build and adding its download link.

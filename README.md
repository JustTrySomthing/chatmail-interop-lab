# Relay Lab

Private Android project for experimenting with Delta Chat and ArcaneChat interoperability. The source starts from the verified [P2P 0.7.23-rc45 workspace](https://github.com/JustTrySomthing/P2P-Releases/releases/tag/v0.7.23-rc45).

Experimental installations use `app.p2p.interop` and display **Relay Lab**. Use a fresh test profile and separate signing credentials. Automatic update checks default to off. The experimental updater points to `JustTrySomthing/chatmail-interop-lab`.

## Build a debug APK

Use JDK 17 or 21 and Android SDK Platform 35. Set `ANDROID_HOME` to the SDK directory.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Use `P2P_INTEROP_KEYSTORE` and `P2P_INTEROP_KEY_PASSWORD` for separately signed release builds.

## Contacts, profiles, and encrypted chats

In **Contacts → Add contact → Send invite**, show your SecureJoin QR code, copy its link, or share it. **Accept invite** can scan a code, read a saved image, or paste a link. Android links that open in Relay Lab are prefilled for confirmation. Existing signed P2P invitations remain readable.

Chatmail contacts appear in **Contacts** and **New chat**. Open a conversation to accept its contact request. **Chats → Chatmail chats** shows encrypted one-to-one conversations. Chatmail Core manages contact keys, delivery, and offline queuing.

**Settings → Your profile** updates the name, photo, and bio in Chatmail Core. A first-run contact link can supply relay candidates, and an active profile can add a relay or login link. The P2P backup preserves earlier P2P data. Its restore leaves the Chatmail database in place.

## Device test status

Use fresh profiles and record the client versions and networks used. SecureJoin can continue after the app starts the handshake. Chatmail profile transfer, encrypted group invites, and live-call interoperability are pending.

| Client | Relay Lab sends invite | Relay Lab accepts invite | Relay Lab sends text | Relay Lab receives text |
| --- | --- | --- | --- | --- |
| Delta Chat | Device test pending | Device test pending | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending | Device test pending | Device test pending |

## Channels and Read Aloud

**Channels → Feed** shows recent posts from joined channels, newest first. **Following** opens an individual channel or joins a standard Chatmail broadcast from a QR code, an image, or an invite link. **My Channel** creates one channel per profile, shares its invite, and publishes text. Existing Social content is reachable through **Previous Social posts**. Channel attachments currently show their filenames.

Read Aloud uses the included offline voices and existing speech animation for regular messages, previous Social posts, Chatmail messages, and channel posts.

| Client | Relay Lab joins and receives posts | Client joins Relay Lab and receives posts |
| --- | --- | --- |
| Delta Chat | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending |

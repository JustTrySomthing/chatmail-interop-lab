# Relay Lab

Private Android project for experimenting with Delta Chat and ArcaneChat interoperability. The source starts from the verified [P2P 0.7.23-rc45 workspace](https://github.com/JustTrySomthing/P2P-Releases/releases/tag/v0.7.23-rc45).

Experimental installations use `app.p2p.interop` and display **Relay Lab**. Use a fresh test profile and separate signing credentials. Automatic update checks default to off. The experimental updater points to `JustTrySomthing/chatmail-interop-lab`.

## Build a debug APK

Use JDK 17 or 21 and Android SDK Platform 35. Set `ANDROID_HOME` to the SDK directory.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Use `P2P_INTEROP_KEYSTORE` and `P2P_INTEROP_KEY_PASSWORD` for separately signed release builds.

## Experiment scope

Start with standard Chatmail QR invites and encrypted one-to-one messages. Check interoperability in both directions against Delta Chat and ArcaneChat. Evaluate call signaling and live calls after messaging works.

## SecureJoin invite experiment

In **Contacts → Add contact → Send invite**, show the standard Chatmail QR, copy its link, or share it. Use **Accept invite** to scan a QR, select a saved image, or paste a link. When Android routes a link to Relay Lab, it is prefilled for confirmation. Older signed invitations can still be accepted. Chatmail contacts appear in Contacts and New chat, and pending requests can be accepted from their Chatmail conversation.

Use fresh profiles and record the app version and network for each direction. “SecureJoin started” means Chatmail Core accepted the invite and started processing it. Open **Chats → Chatmail chats** to see encrypted one-to-one conversations, including conversations started from an invite you shared. Relay Lab enables sending once Chatmail reports the contact key is available and the chat can send. Chatmail Core stores the conversation and handles delivery and offline queuing. Call signaling is pending.

| Client | Relay Lab sends invite | Relay Lab accepts invite | Relay Lab sends text | Relay Lab receives text |
| --- | --- | --- | --- | --- |
| Delta Chat | Device test pending | Device test pending | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending | Device test pending | Device test pending |

## Channels and Read Aloud

**Channels → Feed** shows recent posts from joined channels, newest first. **Following** opens an individual channel or joins a standard Chatmail broadcast from a QR code, an image, or an invite link. **My Channel** creates one channel per profile, shares its invite, and publishes text. Existing Social content is reachable through **Previous Social posts**. Channel attachments currently show their filenames.

Read Aloud uses the included offline voices and existing speech animation for regular messages, previous Social posts, Chatmail messages, and channel posts. The native bridge expects upstream voice IDs and maps them into the two-voice pack during synthesis.

| Client | Relay Lab joins and receives posts | Client joins Relay Lab and receives posts |
| --- | --- | --- |
| Delta Chat | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending |


## Chatmail profiles

Settings → Your profile sends the display name, profile photo and bio through Chatmail Core. Contact photos and bios are displayed from the Core contact records. A first-run contact link can supply relay candidates. Adding a relay or login link to an active profile uses Chatmail Core's transport QR interface.

The earlier P2P backup contains P2P data. It leaves an existing Chatmail profile intact when restored. Native Chatmail profile import, second-device QR transfer, and encrypted group invite workflows still require implementation and testing.

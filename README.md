# Relay Lab

Private Android project for experimenting with Delta Chat and ArcaneChat interoperability. The source starts from the verified [P2P 0.7.23-rc45 workspace](https://github.com/JustTrySomthing/P2P-Releases/releases/tag/v0.7.23-rc45).

Experimental installations use `app.p2p.interop` and display **Relay Lab**. Use a fresh test profile and separate signing credentials. Automatic update checks default to off. The experimental updater points to `JustTrySomthing/chatmail-interop-lab`.

## Build a debug APK

Use JDK 17 or 21 and Android SDK Platform 35. Set `ANDROID_HOME` to the SDK directory.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Run `python3 scripts/check-call-apk.py app/build/outputs/apk/debug/app-debug.apk` to check the packaged WebRTC library and bootstrap. Use `P2P_INTEROP_KEYSTORE` and `P2P_INTEROP_KEY_PASSWORD` for separately signed release builds.

## Contacts, profiles, and encrypted chats

In **Contacts → Add contact → Send invite**, show your SecureJoin QR code, copy its link, or share it. **Accept invite** can scan a code, read a saved image, or paste a link. Android links that open in Relay Lab are prefilled for confirmation. Existing signed P2P invitations remain readable.

Chatmail contacts appear in **Contacts** and **New chat**. Open a conversation to accept its contact request. **Chats** shows encrypted one-to-one conversations, and **Groups** creates and opens encrypted Chatmail groups. To share a group’s SecureJoin QR code or link, open the group and tap the invite icon. The receiving client can scan the QR code or open the link. Set an optional description when creating or editing a group. Tap the name in an open group to read it. Relay Lab uses Chatmail Core’s shared group description field.

Chatmail Core handles message delivery, offline queuing, encrypted attachments, quotes, reactions, and group membership. Relay Lab still renders its own message bubbles, reaction animations, media controls, thumbnails, and web previews. Relay Lab trims and compresses media on the device before submitting it as a standard attachment.

Verified Chatmail mappings let earlier one-to-one conversations use Core while retaining their local history. Groups created with the earlier P2P protocol keep their original transport and history. Create a group in the current **Groups** screen to use it across clients.

**Settings → Your profile** updates the name, photo, and bio in Chatmail Core. A first-run contact link can supply relay candidates, and an active profile can add a relay or login link. The P2P backup preserves earlier P2P data. Its restore leaves the Chatmail database in place.

## Interoperability code

Use the shared Chatmail Core adapter for both Delta Chat and ArcaneChat. Chats, Groups, Channels, and Talk share Core account access. Contact profiles use the same parser, and all three SecureJoin invite screens share the QR and link controls. Keep Relay Lab presentation and media tools in the app. The signed P2P path supports earlier conversations, invites, and backups. Keep new interoperability features on the Core path.

## Device test status

Use fresh profiles and record the client versions and networks used. SecureJoin can continue after the app starts the handshake. The table records device testing separately from build and unit checks. Group invitations, membership changes, and exchange of text, replies, files, and reactions still need cross-client device tests.

| Client | Relay Lab sends invite | Relay Lab accepts invite | Relay Lab sends text | Relay Lab receives text |
| --- | --- | --- | --- | --- |
| Delta Chat | Device test pending | Device test pending | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending | Device test pending | Device test pending |

## Channels and Read Aloud

**Channels → Feed** shows recent posts from joined channels, newest first. **Following** opens an individual channel or joins a standard Chatmail broadcast from a QR code, an image, or an invite link. **My Channels** lists the channels owned by your profile, with pinned channels first. Use **Create a Channel** to add another. Open a channel to see its posts and share its invite, or use the pencil to edit its name and description. Open **Write a post** to compose in the bottom sheet. **Add media** attaches images, audio, or video with an optional caption, and the shared emoji picker inserts standard Unicode text. Relay Lab uses standard Chatmail posts and sends each selected attachment as its own post, with the caption on the first. Images and video show previews, links show cards, and audio plays directly in the channel, chat, or group. Existing Social content is reachable through **Previous Social posts**.

Read Aloud uses the included offline voices and existing speech animation for messages, previous Social posts, and channel posts.

| Client | Relay Lab joins and receives posts | Client joins Relay Lab and receives posts |
| --- | --- | --- |
| Delta Chat | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending |


## Talk calls

Talk uses Chatmail Core for call signaling and relay configuration. It bundles the shared Delta Chat and ArcaneChat Android v2.69.0 WebRTC client and uses their WebRTC 129 dependency. Calls start with the camera off. During a connected call, tap the camera icon to turn video on or off. With video on, use the camera switch to change lenses. The screen follows Android’s auto-rotate setting and keeps the camera active during rotation. Video capture stops when Relay Lab goes to the background. App Log records outgoing video resolution and frame rate for troubleshooting.

| Client | Relay Lab calls client | Client calls Relay Lab |
| --- | --- | --- |
| Delta Chat | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending |

For each direction, record ringing and answer time, two-way audio, camera toggles on both phones, and hang-up on Wi-Fi and mobile data. Legacy P2P voicemail remains available for legacy conversations.

# Relay Lab

This is the public Relay Lab repository. Start with [RECOVERY.md](RECOVERY.md) and [the continuation notes](docs/CONTINUE-HERE.md) for the current development source.

Relay Lab is a Chatmail-based Android messenger built to work with Delta Chat and ArcaneChat. It offers encrypted chats and groups, channels, voice and video calls, chat-shared Webxdc apps, and offline read-aloud.

## Download

| Build | Android APK |
| --- | --- |
| **Latest release candidate** | [**Download Relay Lab 0.7.23-rc74**](https://github.com/JustTrySomthing/chatmail-interop-lab/releases/download/v0.7.23-rc74/P2P-0.7.23-rc74-ARM64-Preview.apk) · ARM64 · Android 12+ |
| **Final release** | A final release has yet to be published. |

RC means release candidate. **RC74 is the current build to install.** For release notes and older builds, see [Releases](https://github.com/JustTrySomthing/chatmail-interop-lab/releases).

RC74 (110) combines profile controls, refines message-field spacing, makes Saved stars use the theme accent and toggle saved copies off, and supports Webxdc fullscreen requests with Back and lifecycle cleanup. Includes all RC73 fixes and preserves the established RC58 signing identity.

### Update from the app

Open **Settings → Updates** and tap **Check now**. **Check daily** and **Include release candidates** are enabled by default. Back up chat history before uninstalling an older build.

## Features

- **Chats and groups** with SecureJoin QR and link invites, encrypted attachments, replies, reactions, and group descriptions.
- **Webxdc apps** with a searchable catalog of tools and games that can be shared in Chatmail chats and groups.
- **Channels** for publishing posts and following broadcasts, with QR and link invites.
- **Talk** with voice and video calls and in-call camera switching.
- **Media and accessibility** with inline audio, link previews, media trimming and compression, and offline read-aloud.

Relay Lab uses Chatmail Core for interoperability. Cross-app support varies by client version and feature. See [device test status](#device-test-status).

## Build a debug APK

Use JDK 17 or 21 and Android SDK Platform 35. Set `ANDROID_HOME` to the SDK directory.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Run `python3 scripts/check-call-apk.py app/build/outputs/apk/debug/app-debug.apk` to check the packaged WebRTC library and bootstrap.

Download signed Android builds from the Releases page.

The downloadable APK's source is at the [RC74 release tag](https://github.com/JustTrySomthing/chatmail-interop-lab/tree/v0.7.23-rc74). Use a fresh Relay Lab profile for interoperability testing. The development app uses Android package `app.p2p.interop`, an independent update feed, and daily update checks and release candidates enabled by default.

### Release publishing

Release candidates are published as GitHub pre-releases. See the release checkpoint for verification results and the APK checksum.

The current in-app updater selects release candidates. Verify the RC-to-final upgrade path before publishing a final build and adding its download link.

## Contacts, profiles, and encrypted chats

In **Contacts → Add contact → Send invite**, show your SecureJoin QR code, copy its link, or share it. **Accept invite** can scan a code, read a saved image, or paste a link. Android links that open in Relay Lab are prefilled for confirmation. Existing signed P2P invitations remain readable.

Chatmail contacts appear in **Contacts** and **New chat**. Open a conversation to accept its contact request. **Chats** shows encrypted one-to-one conversations, and **Groups** creates and opens encrypted Chatmail groups. To share a group’s SecureJoin QR code or link, open the group and tap the invite icon. The receiving client can scan the QR code or open the link. Set an optional description when creating or editing a group. Tap the name in an open group to read it. Relay Lab uses Chatmail Core’s shared group description field.

Chatmail Core handles message delivery, offline queuing, encrypted attachments, quotes, reactions, and group membership. Relay Lab still renders its own message bubbles, reaction animations, media controls, thumbnails, and web previews. Chatmail Core handles image compression. Relay Lab follows Delta Chat Android’s video preparation and voice recording settings and also allows clips to be trimmed before sending.

Verified Chatmail mappings let earlier one-to-one conversations use Core while retaining their local history. Groups created with the earlier P2P protocol keep their original transport and history. Create a group in the current **Groups** screen to use it across clients.

**Settings → Profiles → Edit active profile** updates the name, photo, and bio in Chatmail Core. A first-run contact link can supply relay candidates, and an active profile can add a relay or login link. The P2P backup preserves earlier P2P data. Its restore leaves the Chatmail database in place.

## Interoperability code

Use the shared Chatmail Core adapter for both Delta Chat and ArcaneChat. Chats, Groups, Channels, and Talk share Core account access. Contact profiles use the same parser, and all three SecureJoin invite screens share the QR and link controls. Keep Relay Lab presentation and media tools in the app. The signed P2P path supports earlier conversations, invites, and backups. Keep new interoperability features on the Core path.

## Help, compression, and notifications

Open **Settings → Help** to search the in-app guide. **Settings → Compression** sets outgoing media quality for the selected Chatmail profile. **Balanced** is the default. **Data saver** uses the same stronger Core quality setting as Delta Chat. The next-build attachment chooser applies these settings to media selected through **Attach File**, as well as captured media. An explicit trim creates a new clip. Earlier signed P2P image transfers preserve the original file bytes.

**Settings → Notifications** controls alert previews, sounds while a chat is open, replies in muted groups, and incoming calls. Android’s category settings control sound, vibration, and priority for chat, group, and call notifications. Background delivery uses the existing Chatmail connection and scheduled checks. Turn on **Reliable background connection** if you want an ongoing Android notification for a continuous connection.

Relay Lab follows the 1 GiB local Android attachment ceiling used by Delta Chat and ArcaneChat. Relays can reject smaller messages according to their own limits. Webxdc assets stream from the local package, and app-exported files use the same attachment ceiling as a file picked in Android.

## Chatmail controls and recovery

Long-press a message for editing, deletion for everyone, shared pinning, and saving to Saved Messages. Open **Saved** with the top-bar star. It is the same global collection from every conversation. Older stars are migrated to saved copies when Saved opens; unavailable legacy attachments keep their old marker until they can be downloaded and saved. Tap the conversation title for Chat options. Groups include Chat options inside Group info. Chat options has the disappearing-message timer and the shared pin list. Long-press a message containing a point location to open it in a maps app. Chats render basic inline Markdown formatting and loop Telegram TGS stickers. SVG attachments open in the media viewer.

In Group info, choose or remove a group photo. On a channel post, use the emoji button to send or remove your reaction. The Chatmail contact list shows the day a contact was last seen when Core reports it.

In **Settings → Backup**, export a standard Chatmail archive that includes the profile and its history. Keep the archive in private storage. Choose **Restore Chatmail backup** during setup on a fresh install to import an archive. Existing installations retain their profile and use the existing P2P backup controls for earlier P2P data. For a QR transfer, open **Settings → Backup → Add second device** on the original device. On a fresh Relay Lab install, choose **Add as second device** during setup and scan the code on the same network. Standard Chatmail Core QR transfers still need a cross-client device test.

### Multiple profiles

Open **Settings → Profiles** to edit the active profile, switch profiles, or add a profile. A new profile opens Setup, where you can create a Chatmail identity, restore a standard backup, or scan an Add Second Device QR code. Setup's **Switch profile** button lets you return to your original profile even before finishing setup. The original profile keeps its existing conversations, identity, and files. Other profiles have their own Chatmail database, local history, attachments, and Webxdc storage.

Enabled profiles receive messages and incoming calls through the existing background connection or scheduled synchronization. An incoming call notification identifies the receiving profile. Opening or answering it switches to that profile if there is no call or other protected operation in progress. Declining keeps the current profile selected. In **Settings → Profiles**, disable an inactive profile to stop its background connection and alerts, or enable it to resume. Finish a call, a backup or device transfer, or a message that's currently sending before switching. Android permissions and the app's updater apply to the whole installation. Creating, switching, and restoring profiles on Android still need device testing.

## Feature parity countdown

The Android comparison uses Delta Chat and ArcaneChat 2.69.0. This list tracks the known implementation gaps from that comparison. Cross-app phone checks are recorded separately below.

| Feature | Development status |
| --- | --- |
| Native message forwarding | Implemented. Long-press a Chatmail message and choose **Forward**. |
| Chatmail contact cards | Implemented. In the next build, use **Contacts → contact options → Share contact** and choose a destination. Open a received card and choose **Add to Chatmail contacts**. |
| Message Info | Implemented. Long-press a Chatmail message to see its status, timestamps, and recorded read receipts. |
| Per-profile read receipts | Implemented under **Settings → Privacy**. Fresh Relay Lab profiles start with receipts off. |
| Voice-message playback speed | Implemented in the audio player. Tap the speed to cycle 1×, 1.5×, and 2×. |
| Background delivery for multiple profiles | Implemented. Enabled profiles receive messages and incoming-call alerts. Notification actions route to the receiving profile. Android background timing and cross-profile calls still need phone testing. |
| Explicitly disable and re-enable a profile | Implemented under **Settings → Profiles** for inactive profiles. |
| Compare encryption fingerprints | Implemented under **Chat options → Encryption info** for one-to-one Chatmail conversations. |
| List and remove relays | Implemented under **Settings → Profiles → Edit active profile → Manage Chatmail relays**. The final relay is protected. |
| Set channel photos | Implemented in **Edit channel** using the standard Chatmail channel image. |
| Show channel post view counts | Implemented for channel owners using Core read receipts. |
| Clone a Chatmail group | Implemented in **Group info**. Review the name, description, and members before creating the new group. |
| Video-call picture-in-picture | Implemented on Android devices that support PiP. Tap the floating-window button during a video call. |

Background delivery is tracked for Relay Lab's no-Firebase setup.

## Device test status

Use fresh profiles and record the client versions and networks used. SecureJoin can continue after the app starts the handshake. The table records device testing separately from build and unit checks. Group invitations, membership changes, and exchange of text, replies, files, and reactions still need cross-client device tests.

| Client | Relay Lab sends invite | Relay Lab accepts invite | Relay Lab sends text | Relay Lab receives text |
| --- | --- | --- | --- | --- |
| Delta Chat | Device test pending | Device test pending | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending | Device test pending | Device test pending |

## Webxdc apps in chats and groups

The bottom-right button jumps to the newest messages when you are reading older history. Near the bottom it opens **Apps** in writable Chatmail chats and groups. Search **Home**, **Tools**, or **Games**, open an app's details, and use **Add to chat** to attach it to the composer. Send the draft to share an app instance. Use **Open app** on its message to run it.

Use the back arrow to return to the chat. The **Apps** button lists open mini-apps and app messages in the current chat. Each shared message is its own instance, so two copies of the same app can have different data. There is no Relay Lab-imposed limit on the number of open sessions. Available device resources still apply. If WebView needs to run one at a time to preserve isolation, reopening a minimized app reloads its page. Android home-screen shortcut creation has been removed; use Relay Lab’s Apps desktop or open shared apps from their chats. Existing Android shortcuts can be removed through the launcher.

The catalog is the same index used by Delta Chat's default Webxdc Store. Relay Lab ships Delta Chat Android v2.69.0's JavaScript API and WebRTC isolation assets and uses Chatmail Core for native Webxdc messages, status updates, and realtime data. Relay Lab hosts each message in its own isolated WebView origin. A mini-app can request camera, microphone, location, or motion access while open. Relay Lab asks for consent for that mini-app and checks Android permissions for camera, microphone, and location. Android's approximate location setting is supported. Allowing motion reloads the mini-app so its page can use the sensors. Minimizing the mini-app or leaving Relay Lab closes an instance that has been granted any of these permissions. Permission use still needs Android device testing. Seven catalog apps, including Pixel and Connect4, passed offline Core tests. Catalog downloads are validated against the actual response and archive. For native Chatmail attachments, Relay Lab uses the same 1 GiB local ceiling as Delta Chat and ArcaneChat Android. The mail relay determines which messages it will deliver. Android WebView isolation, game interaction, and multiplayer sync across installed clients still need device testing.

## Personal apps and Saved

**Apps** in bottom navigation opens your personal desktop. Tap **+ Add apps** in the top bar or the empty desktop to browse the catalog, then **Add to my apps**. Each install creates a separate standard Webxdc message in your self-chat. Chat-shared instances keep their own data and recipients. App titles identify **Only you** or the owning chat.

Hold and drag the whole icon tile into any grid cell, leaving empty spaces wherever you want. Hold at either page edge to move between pages. Release a long press without dragging to open the themed options menu. Drop an app onto another to create a folder; folders can be named, renamed, and dissolved. Swipe between pages or use the page indicators. More pages are added as needed. **Remove icon** hides the shortcut without deleting the message or app data; **Manage app icons** restores hidden icons. **Delete app and data** permanently deletes the selected personal instance after confirmation; other copies and chat-shared apps remain. Complete storage cleanup requires a current Android System WebView; unsupported versions prompt for an update before deletion. Layout is per Relay Lab profile and is not a cross-client desktop layout format.

The top-bar star opens **Saved**, including messages, notes, files and saved app copies from all conversations. Chat and channel save actions use Core `save_msgs`; old non-Core items are copied into the self-chat using normal messages. A filled star means already saved. **Remove saved copy** requires confirmation and only targets a message in the self-chat. Personal app installations are shown on Apps, while saved copies of shared apps stay in Saved. Saving a shared app uses Core copy semantics; it is not a shortcut to the original live shared instance.

## Channels and Read Aloud

**Channels → Feed** shows recent posts from joined channels, newest first. **Following** opens an individual channel or joins a standard Chatmail broadcast from a QR code, an image, or an invite link. **My Channels** lists the channels owned by your profile, with pinned channels first. Use **Create a Channel** to add another. Open a channel to see its posts and share its invite, or use the pencil to edit its name and description. Open **Write a post** to compose in the bottom sheet. The next build uses **Add attachment** with **Take Photo**, **Record Video**, **Record Audio**, and **Attach File**, including existing documents and media with an optional caption, and the shared emoji picker inserts standard Unicode text. Relay Lab uses standard Chatmail posts and sends each selected attachment as its own post, with the caption on the first. Images and video show previews, links show cards, and audio plays directly in the channel, chat, or group. Existing Social content is reachable through **Previous Social posts**.

Read Aloud uses the included offline voices and existing speech animation for messages, previous Social posts, and channel posts.

| Client | Relay Lab joins and receives posts | Client joins Relay Lab and receives posts |
| --- | --- | --- |
| Delta Chat | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending |


## Talk calls

Talk uses Chatmail Core for call signaling and relay configuration. It bundles the shared Delta Chat and ArcaneChat Android v2.69.0 WebRTC client and uses their WebRTC 129 dependency. Calls start with the camera off. During a connected call, tap the camera icon to turn video on or off. With video on, use the camera switch to change lenses. The screen follows Android’s auto-rotate setting and keeps the camera active during rotation. Video capture stops when Relay Lab goes to the background. App Log records outgoing video resolution and frame rate for troubleshooting.

| Client | Relay Lab calls client | Client calls Relay Lab |
| --- | --- | --- |
| Delta Chat | Passed on device (user-reported, 2026-11-16) | Passed on device (user-reported, 2026-11-16) |
| ArcaneChat | Device test pending | Device test pending |

The Delta Chat report confirms a successful call initiated by each client. Client versions, network type, and individual video checks were not recorded.

For each direction, record ringing and answer time, two-way audio, camera toggles on both phones, and hang-up on Wi-Fi and mobile data. With two enabled Relay Lab profiles, call the inactive one and check both notification actions. Confirm that Answer uses the receiving profile, Decline keeps the current profile, canceled calls stop ringing, and disabled profiles stay disconnected. For both the selected and inactive profiles, confirm that unexpired notifications still route correctly after Android recreates Relay Lab. Leave the app mid-ring on the selected profile and check that the notification appears and keeps the original ring deadline. Legacy P2P voicemail remains available for legacy conversations.

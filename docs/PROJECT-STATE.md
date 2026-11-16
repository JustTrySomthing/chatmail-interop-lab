# P2P project state — 2026-11-15

Current release: rc42 (78). The rc30 device report established one successful call on that build. Playback, call alerts, and background delivery on rc42 still require device verification.

## rc45 contact invite card

- Send invite shows a larger QR code on a rounded white card, with the profile photo or initial above the code and a personalized scan label below it. The card shows when the invite expires.
- Copy code and Share transfer the full invite. The QR can still open in a larger scan view. The existing signed invite format is preserved.
- All 115 automated tests passed. Android lint reported zero errors. The signed APK passed the signing and WebRTC startup packaging checks. The invite test checked the scan label, hidden raw code, clipboard contents, and share payload.

## rc44 contextual actions and reply reminders

- Long presses open a themed action dialog on chat and group conversations, message bubbles, and Social posts. The dialog offers actions that apply to the item and its current state.
- Reply reminders offer one hour, one day, or one week. A reminder opens the conversation or the post reply composer.
- The Android job persists across device restarts. Reminder alerts follow global notification mute and quiet hours.
- All 115 automated tests passed. Android lint reported zero errors. The signed APK passed the signing and WebRTC startup packaging checks. Automated tests covered long-press actions, unread state, attachment downloads, and persisted reply reminder scheduling.

## rc43 Talk controls and animated WebP

- Clear recent uses the Recent calls surface and label colors. The circular phone button is the only redial control in each history row.
- Animated WebP images retain the original bytes during attachment preparation. They use the GIF playback path in chat and the media viewer, with a Play button in Social posts. Static WebP images keep the photo viewer.
- All 114 automated tests passed. Android lint reported zero errors. The signed APK passed the signing and WebRTC startup packaging checks. Automated tests covered redial targets, animated WebP preservation, and the Social playback control.

## rc42 clear recent calls

- Talk aligns the Recent calls heading left and the Clear recent button right. Confirmation clears the device's call history while keeping voicemail.
- All 113 automated tests passed. Android lint reported zero errors. The signed APK passed the signing and WebRTC startup packaging checks. The Talk layout and history-clearing flow passed the automated UI check.

## rc41 shared toolbar and app-wide mute

- The same Bell, Search, Contacts, and Settings actions appear on the main pages, in conversations, and throughout Settings and Contacts. Search follows the current screen. Pins stay with the conversation list and menus.
- The app-wide mute clears existing app alerts, suppresses future alerts and automatic update prompts, and stops the Talk ringtone. Android's silent ongoing call and file-transfer indicators continue to track their active services.
- Talk centers Recent calls in a themed pill and places each date and divider in a slim opaque strip.
- All 113 automated tests passed. Android lint reported zero errors. The signed APK passed the signing and WebRTC startup packaging checks. Notification and call ringtone behavior still needs physical-device verification.

## rc40 Social media and page notification controls

- Social posts show inline photo previews, GIF and video thumbnails with Play controls, and playable audio rows. An available file with an unextractable video frame still has a Play control. Playback and preview support depend on the device and player codecs.
- My posts sits on an opaque themed label. Start a chat and Create a group use the Add friend button style and open the existing sheets.
- Chats, Groups, and Talk have independent persistent mute bells. Chat and group mutes suppress alerts for their own messages, reactions, and attachments. A muted Talk call skips foreground ringing and keeps a silent notification with Answer and Decline in the background.
- All 112 automated tests passed. Android lint reported zero errors. The signed APK passed the signing and WebRTC startup packaging checks. Media codecs and call alert behavior remain unverified on a physical device.

## rc39 system bars and page transitions

- Full-screen settings and Contacts are in-app pages with a shared toolbar and wallpaper. Page switches reuse the activity window.
- The top bar covers the status inset and a solid strip covers the bottom navigation inset when the tab bar is absent. The main navigation keeps drafts and scroll state when temporarily replaced by a full-screen page.
- All 110 automated tests passed, including repeat Settings and Contacts switches using the same toolbar and draft restoration on return to a thread. Android lint reported zero errors. The signed preview passed the WebRTC bootstrap and signing certificate checks. Physical-device layout remains unverified.

## rc38 neutral surfaces preview

- Neutral cards and outgoing message bubbles use the same gray in each theme. The selected accent still colors actions and received messages.
- Empty folder, conversation, and Social screens center the empty-state bubble in the content area. Empty Talk history and contact, voicemail, and block-list messages share the same treatment.
- All 109 automated tests passed, including empty-state position checks on compact, tall, and landscape layouts. Android lint reported zero errors. The signed preview passed the WebRTC bootstrap and signing certificate checks. Physical-device layout remains unverified.

## rc37 preview

- Packages the Appearance palette lint fix and the accumulated rc31–rc36 Android updates.
- All 109 automated tests passed, Android lint reported zero errors, and the signed APK passed the signature and WebRTC bootstrap checks.

## rc36 follow-up source audit

- Replaced the palette’s unused constraint-measuring container with a regular Box to fix the Android lint error. The released APK stays at rc36 (72).
- All 109 tests pass and debug lint reports zero errors. The signed rc36 APK and its recovery archive were independently reverified.

## rc36 Trash actions preview

- Both Trash tabs keep Restore all and Empty trash visible. They are disabled when there are no eligible trashed conversations. The confirmation and local history removal stay scoped to the current tab.
- All 109 automated tests passed, including empty and populated Trash tabs, confirmation, and deletion scoped separately to Chats and Groups. The signed preview passed the WebRTC bootstrap and signing certificate checks. Physical-device layout remains unverified.

## rc35 Settings and dialogs preview

- Settings removes the surrounding panel and gives buttons more width. Labels fit a single line, and the column count adapts to available width and system text size.
- Standard alerts share the themed surface and scrollable body, including deletion, background, and download dialogs. Core dialog actions and fields use the same corner shapes as the rest of the app.
- All 108 automated tests passed, including Settings labels on three screen layouts and access to the final background dialog action on a short screen. The signed preview passed the WebRTC bootstrap and signing certificate checks. Physical-device layout remains unverified.

## rc34 Contacts preview

- Contacts, the Talk Call picker, and the New chat picker use the same themed contact cards and round outlined action icons. Talk offers Call and Chats offers Message.
- Contacts keeps invite handling, search, and an overflow menu for management. Blocked contacts have no Call or Message shortcut and are excluded from the bottom-sheet pickers.
- All 107 automated tests passed, including contextual actions and their visibility in Contacts and the bottom sheets. The signed preview passed the WebRTC bootstrap and signing certificate checks. Physical-device layout remains unverified.

## rc33 interface preview

- Chat and group folders, conversation views, and Social tabs use compact rounded empty messages. Empty folders have no duplicate actions. Friends keeps one Add friend button, and media selection stays inside the new-post sheet.
- Talk and Social buttons share the chat tab corner shape and height. The group-name field shares the search field corner shape.
- All 106 automated tests passed, including empty folders, the single Add friend action, and media selection inside the post sheet. The signed preview passed the WebRTC bootstrap and signing certificate checks. Physical-device layout remains unverified.

## rc32 Recent calls preview

- Recent calls use local date sections, themed cards, profile photos or initials, outlined call icons, and localized timestamps. The full row starts a fresh call through the existing Talk permission flow.
- History uses its existing schema. Direction comes from the saved outgoing flag, and missed status comes from the saved call result.
- All 105 automated tests passed, including date grouping, direction labels and callback actions. The signed preview passed the WebRTC bootstrap and signing certificate checks. Physical-device layout remains unverified.

## rc31 backup preview

- Settings → Backup keeps two actions, Create backup and Restore backup. Creation confirms the passphrase and uses Android’s document picker. The password is required to restore.
- New backups include the profile, contacts, groups, chat and social history, locally available attachments and voicemail, and call history. Chatmail transport IDs and pending sends are excluded. Restored contacts need fresh Chatmail invites to reconnect.
- The metadata uses the existing password-based cipher and has a 16 MiB encrypted size limit. Attachments are authenticated and streamed in bounded chunks. Restore stages them privately, then commits validated data on a fresh installation. The legacy v1/v2 restore path remains available.
- 104 automated tests passed, including archive round trips, tamper and truncation rejection, legacy input, and Settings navigation. The signed APK passed the WebRTC bootstrap and signing certificate checks. On-device file picker and cross-phone restore remain to be verified.

## Recent history

- rc28 reconstructed an editable project after original rc27 workspace was unavailable. Its R8 build removed JNI Zero helpers and crashed on WebRTC startup.
- rc29 disabled shrinking and preserved JNI Zero bootstrap. Native startup was repaired, but call setup and Clear log regressions remained.
- rc30 restored call timing, candidate-less answer handling, audio error callbacks, safe call diagnostics and Clear log. User has now confirmed a successful call.
- Recovery documentation and manifests were added after rc30 without altering application source or the published APK.

## Product constraints to preserve

Android Kotlin/Compose app using Chatmail for encrypted messaging and WebRTC for Talk. Bottom sections: Talk, Chats, Groups, Social. Do not revert to the old no-relay experimental transport. No Firebase or companion notification app. No paid service dependency introduced without user agreement.

Settings and contacts use full pages; simple choices use bottom sheets. Persistent top navigation: contextual create, search, contacts, settings. Preserve theme consistency, readable contrast, selected font sizing, 18 color choices and opaque bubbles/cards. Buttons and bubbles have no outlines; text fields may. User backgrounds appear on full pages and main tabs, with optional 0–100% mode-colored overlay (10% default) and preview. Top/bottom bars remain solid. Do not retint image assets themselves.

Chats/groups: left/right offset bubbles, caption and attachment together, consistent padding, speaker/reaction/star controls, received reactions at bottom right, no reply-arrow feature. Feed/Gallery/Starred share Inbox/Archive/Trash styling. Conversation title has name without avatar. Bell toggles notifications. Long press bubble background offers local Delete/Cancel; text supports selection/copy. Preserve smooth swipe action backgrounds and archive/trash/block behavior.

Social: My Page/Friends/Feed, no friend count, Feed has no composer; it shows friends' posts. Preserve polished post appearance. Local delete is long press rather than X. Friends have explicit remove/mute controls. Search covers Social.

Talk: Voicemail and Call are two independent buttons, not a selected toggle. Voicemail bottom sheet lists received voicemail only, separate from chat; unread indicators direct users to it. Recent calls are grouped by local date and start fresh calls. Preserve microphone mute, speaker, progress animation and genuine call-state labels. Do not sacrifice connection reliability for shorter setup delay.

Settings labels: App log, Connections, P2P address, Read aloud, Updates, Recovery. Two columns; Profile/Setup first; Updates/App log at bottom. App log includes normal activity and errors, with copy and clear controls. Only two built-in speech voices; male default. Dark mode/Cosmic Drift/purple defaults were requested. Verify existing behavior before changing; this list records user constraints, not proof every UI path was exhaustively checked.

## Outstanding work

No new application change requested in the recovery task. Do not invent a new APK. On a future bug report, reproduce narrowly against rc30 and retain working fixes. Device behavior beyond the reported successful call still needs evidence if relevant. Original pre-rc28 signing key and exact rc27 Kotlin source were not recovered; never substitute old source for this current baseline.

## Where to work

Read ../CONTINUE-HERE.md and ../REBUILD.md. Core files: app/src/main/java/app/p2p/TalkAudio.kt, TalkCalls.kt, AppLog.kt, MainActivity.kt, P2pModel.kt. Find actual paths with rg --files if layout changes. The app/build.gradle.kts and scripts/check-call-apk.py define critical release protections.

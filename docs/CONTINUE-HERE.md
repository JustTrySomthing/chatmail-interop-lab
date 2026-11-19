# Continue Relay Lab development

## Channel reactions and private replies

Channel posts display the shared reaction totals supplied by Chatmail Core. The post menu in Feed and Following offers **Reply Privately** for eligible incoming posts. It opens a one-to-one chat with the publisher and a removable post quote. It sends only after the user taps Send. The composer rechecks that the quoted post belongs to the original channel and that the selected recipient is its publisher.

Debug APK assembly, WebRTC packaging, and 226 automated tests passed. The added tests cover aggregate counts, private reply eligibility, and the removable quote in the chat composer. Phone interoperability testing with Delta Chat and ArcaneChat is pending. The latest published candidate is RC76.

## RC76 (112): original attachments, Help and Privacy

Release verification: debug and signed preview assembly passed; all 223 tests passed with zero failures/errors/skips. WebRTC APK packaging and established certificate checks passed. APK `P2P-0.7.23-rc76-ARM64-Preview.apk` SHA-256: `44e750a7aeca90204c7b239fe2464672db0bb8338c7f2da6a1c915d9d3e76d06`. The build daemons stopped during packaging at 1536 MiB; rerunning the completed compilation/packaging with 1024 MiB succeeded. Keep unit tests in a separate 512 MiB run.

Added a separate outlined Attach Original File action below the four standard attachment choices, with a divider and plain-language size/data warning. Regular Attach File retains compression settings; original selection passes asFile=true through the existing draft/Core File route, bypassing image/video media compression. Choice state resets on completion/cancellation and each new chooser session. Existing preview labels show Original file; original drafts do not offer trimming. Shared chooser covers chats, groups and channels.

Expanded searchable Help from 13 to 28 questions, covering Saved copies, personal/shared apps and desktop management, Media, message actions, archive/trash/mute/block, unread indicators, disappearing versus local retention, encryption limitations, Webxdc internet isolation, original sending/file-size limits, troubleshooting and profile deletion. Corrected original-quality advice and current video/update wording. Privacy now links to existing Notifications, Block List, Storage, Profiles and Help, with clear scope explanations. No new app lock or biometric feature is introduced.

Validation: debug assembly and all 223 automated tests passed with zero failures/errors/skips, including the updated 320 dp attachment-menu test and existing original-file/compression checks. Physical-device original-file sending remains pending. Included in RC76 (112).

## RC75 release checkpoint — November 18, 2026

RC75 (111) includes the unified attachment menu with compression preserved, clearer button labels, group leaving and channel management, mute/archive fixes, confirmed profile deletion, optional local storage cleanup, unread Channels indicators, and a more prominent in-call video control. Includes all RC74 fixes and keeps the established RC58+ signing identity. Install over RC74 without uninstalling. Android package app.p2p.interop; ARM64; Android 12+; target/compile SDK 35.

Release verification: debug assembly, preview assembly and all 223 unit tests passed (zero failures/errors/skips). WebRTC packaging and established certificate checks passed. APK: `P2P-0.7.23-rc75-ARM64-Preview.apk`; SHA-256: `c08b11ba5d724e8bc4f35e5fdad159c2aac65c0240e4a0cf6964acdf698bf079`.

Physical device tests for group/channel operations, profile deletion, local-retention expiry and attention indicators remain pending. Automated checks do not establish cross-client interoperability.

## Included in RC75: attention indicators and video control

User prioritized clear unread/missed-event indicators and deferred the remaining lower-priority parity additions. Existing chat/group unread counts and navigation dots, missed-call styling/Talk dot and voicemail badges remain. Fixed the main Channels tab omission: native channel unread state now feeds its navigation dot, refreshes during background/foreground Core catalog sync, and clears as channels are opened or removed. Following already shows channel unread counts.

Audio-call UI now offers a full-width themed Turn Video On/Off button with a 32 dp camera icon and at least 56 dp height. The fullscreen video overlay camera icon is also 32 dp and high contrast, retaining its existing hit target/layout. Camera permission and signaling behavior are unchanged. Validation: debug assembly and all 223 automated tests passed with zero failures/errors/skips. Included in RC75 (111).

## Included in RC75: group, channel, profile and storage controls

Implemented the first requested Delta-help parity priorities. Group details now offer confirmed Leave Group, backed by Core self-removal, retaining history and showing membership status. Channel details offer Unfollow Channel for subscribers and Delete Channel for owners, with explicit consequences; owned posts have themed Post Options with Edit Post and confirmed Delete Post. Subscriber removal leaves through Core before deleting history; owner deletion does not claim to erase subscribers’ saved copies.

Mute changes now reach Core before the local UI changes. Existing local mute settings migrate once; later Core mute changes are reflected locally. Muted Archive stays archived when messages arrive, while existing unmuted/Trash behavior is preserved.

Profiles has confirmed local deletion. An active profile switches to another enabled profile (or creates a fresh one) first. Background models stop before files are removed; notification routes, profile database, identity, media and tracked Webxdc site data are cleaned up. The original profile registry supports removal without resurrecting it. Original media directories preserve nested additional-profile data. Saved copies on other devices and exported backups are unaffected. Site-specific WebView deletion is required when apps have local storage; an unsupported WebView leaves the profile disabled with a retryable error.

Settings → Storage adds optional per-profile Core local retention: keep all, 1 hour, 1 day, 1 week, 30 days, 1 year. Default remains off; enabling asks for confirmation covering existing history. Core 2.69.0 excludes Saved/self-chat, which also holds personal apps. Existing cache reconciliation removes expired local message/media copies. Help text is updated.

Validation: debug APK assembly and all 223 automated tests passed, with zero failures/errors/skips. Added tests cover profile deletion confirmation/cancellation, registry persistence after removing the original profile, media/database isolation, app-storage isolation, Core group membership and muted/archive behavior. Settings layout tests still pass with the Storage tile. The initial 512 MiB compiler run ran out of heap; final compilation used the normal build heap and the separate test run passed. Recovery manifest: 210 files verified.

API semantics were checked against the bundled Core v2.69.0 source, not assumed from main. Physical cross-client leave/unfollow, channel edit/delete, archive reception, WebView cleanup and retention expiry still need phone testing. Included in RC75 (111).

## Delta Chat help review — November 18, 2026

See [DELTA-HELP-PARITY-REVIEW.md](DELTA-HELP-PARITY-REVIEW.md) for the source-based comparison against the current Delta Chat FAQ. It records missing/partial controls, the local-mute/Core-archive mismatch, pending-source original-quality selection gap, intentional product differences, and device checks still required. Review only: no application behavior changed or new APK published. Do not treat the earlier completed parity countdown as exhaustive.

## Included in RC75: button label consistency

Talk’s primary action now reads Make a Call. My Channels retains Create a Channel with no leading plus icon. Audited static button/action labels across composers, settings, setup, media, app menus, profiles, invites and calls for Title Case, preserving acronyms and short connecting words (for example, Create a Channel). Updated existing UI-test selectors. No routes, compression behavior, user-supplied names or message content were changed. These changes and the attachment chooser are included in RC75 (111).

Validation: debug assembly passed; all 217 automated tests passed with zero failures/errors/skips. Updated static-label selectors while retaining the generated Save attachment accessibility label; stabilized the Profiles test by waiting for startup.

## Included in RC75: unified attachment chooser

Chats, groups, channels, and the retained Social post composer now use the same themed Add attachment sheet: Take Photo, Record Video, Record Audio, Attach File. The all-files picker supports multiple selections and retains URI read grants. Capture results retain their original destination; channel results are never assigned to a different selected channel. Cancelled camera output is deleted. Existing draft previews, trimming, and send paths remain in place.

Attach File describes media with asFile=false, so generic file selection does not bypass the existing compression policy. Chatmail Core still handles photos; video preparation uses the profile policy; AudioCapturePanel reads the profile quality/bitrate. Ordinary documents, Webxdc packages, GIFs and untrimmed imported audio retain existing handling. No compression settings or defaults were changed. Contact sharing moved to Contacts → contact options → Share contact → destination, leaving the four attachment actions uncluttered.

Validation: all 217 automated tests passed with zero failures/errors/skips; final debug APK assembly passed. All 207 recovery checkpoint files verified. Added four-action menu routing/visibility coverage and updated the Social composer navigation check. Earlier combined and compile-plus-test runs lost their Gradle daemon under memory pressure; the fresh single-worker test run with a 512 MiB Gradle heap passed in 53 seconds.

These changes are included in RC75 (111). Camera hardware, permission dialogs, and actual send-time compression on phones require device testing.


## RC74 release checkpoint — November 18, 2026

RC74 (110) contains all post-RC73 source changes described below: unified Profiles controls, compact composer spacing, themed toggleable Saved stars, and Webxdc fullscreen requests with lifecycle cleanup. No scheduled sending or game-control modifications were added. Install over RC73 without uninstalling; established RC58+ signer retained.

All 216 automated tests passed with zero failures/errors/skips. Debug and signed preview APK builds passed. The combined assembly daemon stopped during the preview stage; the separate preview run succeeded. APK package app.p2p.interop, version 0.7.23-rc74 (110), min API 31, target/compile API 35. APK signature and WebRTC packaging verified. Device testing remains pending for profile management, actual Saved synchronization, and Quake fullscreen/rotation; automated checks do not establish these results.

APK: P2P-0.7.23-rc74-ARM64-Preview.apk. SHA-256: `c40a3affaf2d1e49e832265435772888430899fac4e3c8fdde35c43da25ae268`. Source tag: v0.7.23-rc74 on securejoin-invites. README and main recovery pointers identify this release.

## Included in RC74: Webxdc fullscreen requests

User reported Quake III on RC73 with working invisible touch regions and TypeErrors at index.html lines 406/445. Inspected catalog package wofwca-quake3-v5.1.0.xdc: move/look are empty transparent divs, and those two lines call requestFullscreen. The line-163 CSP warning is its external Google Fonts stylesheet; keep network restrictions intact. The line-784 generic engine log is insufficient to diagnose. Do not claim visible controls were missing because Relay Lab blocked them.

Fixed actual host omissions: delegate fullscreen to the same-origin app iframe and declare fullscreen=(self) in the response policy; handle WebChromeClient custom fullscreen views with an owned black overlay, system-bar hiding/restoration, and Back to exit. Reject hidden/background sessions and duplicate/attached views. Clean up on stop, hiding, renderer/WebView release, and disposal; callback is delivered once and the custom surface detached. Exiting fullscreen while a landscape game remains visible preserves landscape immersive bars. No changes to app packages, sandbox network access, sensor/camera/microphone grants, or game controls.

Verification: all 216 automated tests passed with zero failures/errors/skips; debug APK assembly and WebRTC packaging check passed. Added tests for fullscreen attachment/removal/reopening, exactly-once callback cleanup, duplicate/hidden request rejection, and local iframe delegation.

These changes and the Profiles/composer/Saved fixes below are included in RC74 (110). Quake fullscreen behavior needs physical Android/WebView testing; automated surface tests are not that verification.

## Included in RC74: Profiles, composer spacing and saved-star toggles

User deferred scheduled sending; no scheduling controls or behavior were added. Settings now has one Profiles entry. Its existing management dialog adds Edit active profile, which opens the existing name/photo/bio/relay editor; switching, adding, and enabling/disabling inactive profiles retain their existing behavior. No profile deletion or data migration was added.

The conversation composer uses a 48dp-minimum outlined BasicTextField with 10dp vertical internal padding, 12dp top outer spacing and 10dp bottom spacing. Multiline entry, draft state, emoji/focus behavior, attachments, and send routes are retained. Saved stars use MaterialTheme primary instead of hard-coded gold and remain actionable after saving. Chat/group message and Media stars toggle native saved copies; channel post/feed stars use the same save/remove behavior. The shared deletion helper verifies the copy belongs to self-chat before deletion and clears cached saved references without deleting source messages. Migration still uses idempotent save behavior rather than toggling. Save and remove show progress, with retries available on failure.

Verification: all 214 automated tests passed with zero failures/errors/skips; debug APK assembly passed. Added navigation coverage for the single Profiles entry and edit route; checked saved-star progress/removal and real Media save/unsave gestures, composer typing/draft retention, and two-column Settings at narrow widths and enlarged fonts. Inspected the 320dp Settings render. Initial checks caught and corrected a nested-scroll conflict in Profiles and updated the old saved-star accessibility label assertion. Core-backed save/delete and profile switching still need phone verification.

These changes are included in RC74 (110). Physical-phone checks remain pending.

## RC73 release checkpoint — November 18, 2026

The user requested the next APK, lifting the earlier hold. RC73 (109) includes every source change since RC72: clearer Saved actions, visible save progress/success/retry, readable two-column Settings, immersive landscape Webxdc apps with portrait restoration, Media filename space/file sizes, Apps button contrast, simplified automatic background setup and incoming-call permissions, and native Chatmail reply reminder/video-call Activity fixes. Detailed implementation notes follow.

Verification: 213 automated tests passed with zero failures/errors/skips. Debug and signed preview assemblies passed. The first combined run lost its Gradle daemon; separate test and assembly runs succeeded. APK package app.p2p.interop, version 0.7.23-rc73 (109), min API 31, target/compile API 35. WebRTC packaging and the established RC58+ signing certificate verified. Previous focused Android lint passed with zero errors (50 warnings, one hint). Physical-device checks remain pending for Moto readability, rotation/system bars, lock-screen/background calls and cross-client behavior; automated checks do not establish those results.

APK: P2P-0.7.23-rc73-ARM64-Preview.apk. SHA-256: `26731785f489d9e7eee012cf2c4f8c64601122528f4e36d09df1aee8b9e21975`. Source is tagged v0.7.23-rc73 on securejoin-invites. Install over RC72 without uninstalling. README and main recovery pointers identify this release.

## Included in RC73: focused regression review

User asked for concrete bug fixes only, with no random changes or redesign. Two production edits: ReplyReminder.valid now accepts UUID Chatmail one-to-one chat keys as well as legacy P2P peer IDs (previously Remind me to reply was omitted for native direct chats); video calls obtain their screen-awake window from LocalActivity rather than casting LocalContext, which may be wrapped. Added native conversation/message reminder scheduling, rescheduling, persisted extras, legacy compatibility, and malformed-ID regression tests.

Review covered recent setup/background permissions, call notifications, lock-screen and fullscreen state cleanup, Saved/Media paths, and reminder routing. Android lint initially found one error in the video-call Activity lookup and 50 warnings. Warnings were examined rather than suppressed: intentional ARM64-only packaging, optional PiP animation advice, style/API migration suggestions, application-context ownership, inlined notification permission constants guarded at runtime, immutable replacement of saveable ArrayLists, the existing WebView feature guard, and parent-handled SVG gestures. No dependency upgrades or unrelated UI changes made.

Verification: Android lint passes with zero errors (50 existing warnings and one hint). The combined lint/test/build daemon stopped unexpectedly after lint completed; the fresh run passed all 213 unit/UI tests with zero failures/errors/skips and assembled the debug APK. WebRTC APK packaging verification passed. These fixes are now included in RC73 (109). Physical-device interoperability, lock-screen/background delivery, and rotation behavior remain separate verification tasks. Do not claim the entire app is bug-free based on automated checks.

## Included in RC73: simpler Setup and call permissions

Background delivery is now automatic, including boot, package updates, and old installations with the removed enabled=false preference. Removed the Receive in background switch from Setup and the equivalent Background delivery switch from Notifications. The optional reliable foreground connection mode remains in notification settings; it is not a receiving on/off switch. Android battery approval is still needed for the existing unobtrusive continuous connection; fallback network jobs remain available when approval is declined. Do not claim guaranteed delivery after force-stop or manufacturer process restrictions.

Setup uses Relay Lab branding and contrasting highlighted cards, describes camera/microphone use for calls, and detects disabled notification categories as well as global notification denial. Completed connection setup has no redundant explanatory text or switch. A failed start offers Retry. Added Incoming call screen approval on Android 14+, USE_FULL_SCREEN_INTENT, and actual incoming-call full-screen notification routing. Quiet Hours and low-priority/blocked channels cannot trigger full-screen alerts; ordinary message/reminder notifications never do. Only the visible live call UI may show over the lock screen, and it never dismisses the keyguard. Window access is removed when call UI closes or is disposed.

Permission audit: notifications cover calls/messages/reply reminders; microphone covers voice/video calls and recording; camera covers video calls, QR scanning and captures. File selection uses Android's document picker, so broad storage access is unnecessary. Optional Webxdc location permission remains requested when used. Update installation access is requested at installation. Reply reminders use JobScheduler, not exact alarms, so no exact-alarm or Do Not Disturb bypass permission is added. User mute and Quiet Hours choices remain intact.

Verification: all 211 debug unit/UI tests passed with zero failures/errors/skips, and Kotlin compilation passed. Setup rendering inspected in the dark theme; highlighted card contrast corrected. Tests cover background boot/upgrade scheduling with old disabled preferences, full-screen call versus message routing, Quiet Hours, blocked notification categories, and removal of lock-screen access when call UI closes. Physical locked-screen call reception and manufacturer battery behavior remain device-test tasks. Included in RC73 (109), following the user’s new APK request.

## Included in RC73: readable Settings, fullscreen apps, and save feedback

Published APK remains RC72 (108). The user is collecting changes before the next APK; do not publish a new build until requested. This checkpoint includes the clearer Saved actions below.

Settings keeps two columns with 104dp tiles, 15–19sp fitted labels, and two lines for multiword labels; single words cannot split across lines. Item stars in chat/group messages, attachments, and channel posts show saving progress and a contrasting gold saved state. Failed operations clear transient progress and allow retries; native message saving verifies a saved-copy ID before reporting success. Existing standard Chatmail Saved Messages storage is retained.

Media cards place the save star at the preview's top right, give filenames the full lower row, and show file size from metadata or the available local file (Size unavailable when neither exists). Chat/group floating Apps buttons now have an outline and shadow for visibility over images.

Visible Webxdc apps enter immersive mode in landscape, hiding Android system bars, the Relay Lab viewer toolbar, and diagnostics footer. Edge swipes temporarily reveal Android controls. Portrait, minimizing, or closing restores normal bars; individual app HTML remains unchanged. Active sessions remain intact across rotation. Viewer inset padding uses one stable modifier across fullscreen transitions; the rotation test caught recursive UnionInsets when conditionally adding/removing padding modifiers.

Automated verification: all 208 debug unit/UI tests passed, zero failures/errors/skips; Kotlin compilation passed. Narrow-screen Settings and Media screenshots inspected. No APK assembled or published for this checkpoint. Device verification remains pending for Moto readability, Android 36 rotation/gesture behavior, and real-account Saved synchronization. UI tests exercise save progress/success/retry, viewer toolbar restoration, narrow Settings at 280/320/360dp with enlarged fonts, and Media file sizes. The new isolated composable tests use a bare ComponentActivity to avoid launching the entire application's asynchronous UI during component tests.

## Included in RC73: clearer Saved actions

User requested these source changes now and explicitly asked to wait for more changes before making the next APK. The earlier hold was lifted by the user’s RC73 build request. Saved menus now say Open Saved Item and Remove From Saved. The source action resolves the actual Chatmail origin to Go to Chat, Go to Group, or Go to Channel, including when the original message is not cached in the UI. Personal items keep that action hidden. While resolution is pending/unavailable, Go to Source avoids falsely identifying a channel as a chat. Existing navigation and removal behavior is preserved; channel navigation opens the channel, so it is not labeled Go to Post. Targeted PersonalSpace and Saved UI tests passed (24 tests, zero failures/errors/skips). No APK was created at that checkpoint; the changes are now included in RC73.

## RC72 Saved collection redesign

Based on RC71 (f225182). Saved now uses theme surface/ink/accent colors, rounded bordered cards, asynchronous local photo/video previews, attachment-type icons, source conversation labels when cached, timestamps, newest-first ordering, a clearable search field, and horizontally scrollable type filters. A compact item menu provides Open, Original conversation (when available), and Remove saved copy (self-chat only). The removal confirmation and existing Core save/delete/open routes are preserved; this is a presentation update with no wire-format or storage migration. The top-bar star remains the single collection entry; the add button opens the existing personal note/file composer. Added light/dark 320dp UI tests for filtering/search/open/source/cancel/add, and classification/order/isolation tests. Verification: 205 automated tests passed, zero failures/errors/skips. Required debug tests/build and signed preview assembly passed. Inspected Saved dialog screenshots in dark and light themes at 320dp. The first new UI test attempt needed a correction to scroll the lazy filter row before locating an off-screen chip; the corrected full suite passed. Signature and WebRTC packaging checks passed. Phone testing remains pending. APK SHA-256: `2cb4ea38253ef7601c305f51f02d41c85b90d44c34e48e5d7831ce3801487aad`.

## RC71 conversation Media and stable Settings

Based on RC70 (8587bd1). Gallery is now Media and includes all file attachments, Webxdc apps and contact cards, scoped to the selected conversation and excluding deleted items and Webxdc status messages. Generic files, apps and contact cards have distinct fallback icons; filenames wrap to two lines. Installed shared apps open their exact instance; other items retain existing file/playback/download/contact handling. Earlier attachment history remains accessible with Load earlier attachments. Removed the conversation Saved tab, New chat Saved entry, and View Saved actions; the top-bar star is the single collection entry. Per-item save stars remain as save actions/filled indicators, with already-saved stars disabled. Settings always uses two columns, fixed-height tiles, responsive icon positioning and fitted single-line labels; navigation chrome does not expand with system font scaling. Added mixed attachment routing/scoping tests and narrow Settings checks at larger system fonts. Automated verification: 202 tests passed with zero failures/errors/skips. Required debug tests/build and signed preview assembly passed. Inspected rendered Media and two-column Settings at narrow widths, including enlarged system fonts. Signature matches the established RC58+ certificate; WebRTC packaging check passed. Phone verification remains pending. APK SHA-256: `a6b533764f46ccf3b992784e924142d7ff2ab272c190e3051ebee9ae34a46372`.

## RC70 easier dragging and personal app management

Based on RC69 (97c6be7). Page-edge zones widened from 28dp to 56dp (capped at one seventh of viewport width to keep outer cell centers outside the turn zone), hover shortened from 550ms to 250ms, with a translucent destination strip and arrow. Themed menu cards replace plain text actions. Removed next/previous-page and move-to-folder actions; Remove from folder remains. Personal deletion validates the native self-chat and Webxdc message, rejects saved copies and shared chat apps, closes the selected viewer, deletes its Core message, then awaits WebStorageCompat site-specific cleanup for its unique profile/message host and disables its old shortcut. No shared attachment files or global WebView storage are deleted. Unsupported WebViews reject the action before deletion and request an update. Existing external exports/backups are not erased. Removed shortcut creation UI and publishing code, retained legacy shortcut routing. Added confirmation/cancel/selected-instance and native ownership/storage identity checks; drag regression now approaches edges 40px inward. Automated verification: 199 tests passed with zero failures/errors/skips, including light/dark app menu confirmation and cancel, deletion ownership guards, independent profile/message storage hosts, and inward edge drag tests. Inspected actual dialog window renders in both themes. Phone testing remains pending. Required debug tests/build and signed preview assembly passed. Certificate and WebRTC packaging checks passed. APK SHA-256: `0b1fe73d4bd780c694b7cbf64068bb3185eab1b7aed4c5f20b139cd9adbe7fd1`.

## RC69 free desktop placement and stable navigation

Based on RC68 (ab8ba11), including its folders and launcher gestures. Desktop pages now preserve vacant cell positions using empty strings. Moving into an occupied cell swaps desktop items; folder extraction places displaced items in a free cell. Folder dissolve fills free cells without moving unrelated apps. Loading removes unavailable items without compacting other icons. Edge hover accepts horizontal overshoot, repeats from settled pages, and animates page transitions. The bottom bar keeps equal-width, fixed-height tabs, consistent label weight, and single-line adaptive labels; selection cannot increase bar height. Added sparse layout/reload/folder and real cross-page return drag tests; strengthened all selected label tests. Automated verification: 196 tests passed with zero failures, errors, or skips. Includes native-graphics Robolectric tests at 280/320dp widths and normal, 1.3x, and 2x font scales. These are automated UI checks, not device tests. Phone verification of drag feel and edge gestures remains pending. Debug and signed preview assembly passed. APK signature matches the established RC58+ certificate; WebRTC packaging check passed. APK SHA-256: `a13af65720def3189c77140c145dc8556025bbac05b0ff741de662ed71b8d47b`. The initial combined build process stopped during tests; the full test suite and assemblies passed on separate single-worker runs.

## RC67 bottom navigation and Saved stars

RC67 (103) fixes the bottom navigation label width so Channels is fully visible, balances the Groups and Channels icon sizes, and uses stars consistently for Saved across the toolbar, messages, and channel posts. Navigation destinations, unread indicators, saving behavior, and existing data are preserved. Includes the RC66 default-on daily update checks and release candidates, and the established RC58 signing identity. The bottom bar uses equal-width selectable tabs and full-width labels with natural wrapping at larger font settings, avoiding the Material item padding that clipped Channels. Group/channel vectors have larger bounds to compensate for their smaller painted area. Selected/unselected state and unread badges are retained. Added narrow 320dp rendering/navigation checks at normal and 1.3x font scale, including complete text layout and touch target bounds. Verification: 189 automated tests passed, zero failures/errors/skips. Required debug tests/build and signed preview build passed; signature and WebRTC packaging checks passed. Rendered narrow-screen previews checked at both font sizes. Actual phone verification remains pending. APK SHA-256: `14338166a247ad8597421dc80e95b8576cb786c78932f784bc5b1a67df85ed82`.

## RC66 update defaults and Settings polish

User requested daily checks and release candidates enabled by default, plus a more polished Settings design inspired by their OnKlav screenshot. Automatic checks now default true (previews already defaulted true), respecting explicitly stored opt-outs. Settings retains the same 16 destinations and routes, with neutral rounded tiles, distinct accent-colored outline icons, wrapping labels, equal row heights, and adaptive columns for narrow screens/font scaling. No screenshot wallpaper or extra destinations were copied. Automated verification: 187 tests passed (0 failures/errors/skips), including every Settings route/search in dark and light themes at 360dp and the single-column layout at 320dp. Required debug tests/build and signed preview build, APK signature and WebRTC packaging checks passed. Rendered screenshots inspected for spacing, icons and text contrast. Actual phone checks remain pending. APK SHA-256: `1c5e68aedee01e38ea5b2a68bfb307d57325f9c49b11fc57dd0edbc6500fe475`.

## RC65 review checkpoint

The user clarified that Relay Lab is used only by them and a couple of demo testers. Prioritize current behavior; do not add backward-compatibility machinery for obsolete features. Preserve existing test data and the signer when updating.

Reviewed RC64 UI, Core-backed saving, migration, desktop ordering and profile boundaries. Fixed moves from overflow pages onto a full previous page by materializing and atomically saving page assignments/order. Page/hide/order writes share a mutex. Drag gesture closures now refresh with page contents and clamp rows/columns separately. Hidden icons no longer affect page-number clamping. Deleted starred records are not migrated. Legacy social post saves include all attached files, validate media availability before copying, preserve the parent star until all parts succeed, and checkpoint each saved-copy id for resumable retries. Saved attachment actions open Saved when already saved; caption text participates in search. Notes/files without an original reference no longer promise another copy in removal confirmations. Catalog installation and Saved action errors are shown within the open dialog. Profile-keyed composition and standard Core save/delete/Webxdc paths remain in place.

Verification: 184 tests passed (0 failures/errors/skips); required debug tests/build and signed preview build passed; signature and WebRTC APK packaging checks passed. APK SHA-256: `4551e1be2533044b473530a50ee7747d1f3135e11f798ad2d0fa2349fd43e65d`. Tests added for full-page movement and persisted layout, hidden-page stability, deleted-star migration, note removal wording/caption search, complete legacy-post attachment selection and a real pointer drag gesture. Phone/cross-client verification remains pending.

## RC64 personal desktop and global Saved checkpoint

User approved an Apps bottom tab and global Saved bookmark collection. PersonalSpace.kt contains desktop layout, icon rendering, paging/reorder, hidden-icon restoration, Saved list/search/removal confirmation. Personal installs use commitDraft into Core's self-chat; standard sharing and existing Webxdc runtime remain unchanged. A saved shared-app copy is excluded from the personal desktop using originalMsgId. Old stars are migrated on opening Saved; failed migration keeps old stars visible for retry. Native and channel saves use Core save_msgs with savedMessageId deduplication; legacy items use nativeSend and retain their saved copy id.

Personal app order is a profile-scoped thread-pref record; desktop page and hidden metadata are on the local cached message, preserved by normal cache refresh. Layout is local presentation, not a promised cross-client synchronized desktop. Removing icons does not delete data. Saved deletion verifies the target is in the self-chat and calls Core delete_messages; original messages are not deleted. Saved details and original-conversation navigation are separate actions.

Automated verification: 179 tests passed (0 failures/errors/skips), including native-graphics Robolectric UI interaction tests for paging/opening exact instances, icon hiding/restoration, and global Saved/removal confirmation. Required debug tests/build and signed preview assembly passed; APK certificate and WebRTC packaging checks passed. APK SHA-256: `d3cbcc55e6a8fb4adb0c63aef79997de010a8bcaff5748f66f899158e43e9f7d`. Visual inspection additionally corrected desktop dark-theme content contrast and stacked Saved actions for narrow screens. Icon options have an explicit button; long-press dragging still needs phone verification. Required phone checks: personal installs, multiple independent copies, long-press drag, swipe pages, moving to new page, hiding/restoring icons, global Saved from two chats and channel, migration with an unavailable attachment, delete original vs saved copy, profile switching, restore and cross-client Webxdc status updates. Do not claim device or cross-client checks passed from unit tests.

## RC63 open-app parity checkpoint

The user requested removal of artificial restrictions and parity with Delta Chat/ArcaneChat. Removed MAX_OPEN_WEBXDC_APPS=3 and the launch rejection/toast. The existing Compose app switcher and session identity are preserved; opening more apps does not evict existing ones. This aligns the absence of a product-level three-app cap; it does not claim identical Android activity architecture.

Compared current upstream WebxdcActivity.java on November 17, 2026: Delta Chat starts a standard WebxdcActivity; ArcaneChat uses a per-account/message document URI and FLAG_ACTIVITY_NEW_DOCUMENT. Neither launcher imposes our three-app quota. Both clean up their viewers via lifecycle destruction. Relay Lab already pauses hidden viewers and destroys them when their session is closed. Do not add a replacement fixed quota or promise that finite phone memory can hold unlimited live viewers.

Sources: https://github.com/deltachat/deltachat-android/blob/main/src/main/java/org/thoughtcrime/securesms/WebxdcActivity.java and https://github.com/ArcaneChat/android/blob/main/src/main/java/org/thoughtcrime/securesms/WebxdcActivity.java .

User-reported RC62 phone checks (Android 36, WebView 157): Checklist preparation 37 ms / guard wait 25 ms / frame 171 ms; Pixel first opening 53 / 38 / 559 ms; Pong 33 / 20 / 171 ms. All reported loaded=true, API=true, no startup/renderer error, and successful status update retrieval. User confirms repeat opens are quicker. These are user diagnostics, not our emulator measurements; pre-runner latency and cross-phone synchronization were not measured.

RC63 device checklist: open four or more distinct shared apps, switch back to the first, close one and reopen it, and confirm each retains its own saved data. No successful emulator run is claimed.

## RC62 startup checkpoint

RC61 (97) is user-confirmed to open shared apps, with a positive frame height. The user reports 5–10 second opening latency on Android 36 / WebView 157. RC62 (98) keeps the guard alive for the lifetime of P2pApp and prewarms it from app browsing and chats with Webxdc messages. Previously closing the last session destroyed the 500-peer guard and the next open rebuilt it. Delta Chat retains its guard. Renderer loss still clears sessions and replaces the guard; each app still checks isolation before loading.

New diagnostics separate preparation, guard wait, and frame-load elapsed milliseconds. No phone timing or emulator performance result is claimed for RC62. Compare first opening, closing/reopening, and switching apps on device.

Inspected catalog package nelson9608-calculator-xdc-V1.0.0.xdc: index.html requests /assets/ds.js, which is absent from its ZIP. It loads assets/main.js and does not request webxdc.js. Thus api=false is expected for this standalone Calculator, and the missing JavaScript request is an upstream package defect; do not substitute a fake script or relax sandboxing to hide it.

RC62 automated verification: 172 unit tests passed with no failures/errors/skips; debug and signed preview builds passed; signature and WebRTC packaging checks passed; recovery manifest verified 192 files. APK SHA-256: d003e681979eb0d036b297cd96e9004051656f157c38098d75ac6d66d942147d. Device speed measurements remain pending.

## Historical recovery checkpoint

This repository is public. Read RECOVERY.md for exact source and signing recovery instructions. RC58 (94) includes all recovered changes on securejoin-invites, draft PR #1. Main has older application source; use the development branch or RC58 tag.

The recovered Webxdc checkpoint is ed5f9ed4e56106d464ed93ed13cd4cce53998bbc. It includes final source and tests recovered from the prior conversation workspace. No additional source differences remained in that workspace before version preparation.

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

Verify Webxdc blank-page recovery, separate shared app instances, cross-chat switching, file picking and launcher shortcuts on a phone. Record phone verification separately from automated results.

The branch README records user-reported successful Delta Chat calls in both directions on November 16. Exact client versions and networks were not recorded. Other cross-client checks remain pending where indicated in README.

## RC58 signing transition

User authorized a new persistent key after RC56 key recovery failed. RC58 cannot update RC56 in place; export Chatmail history before reinstalling. RC57 remains an unpublished draft. RC58 uses the dedicated signing configuration, with encrypted backup in GitHub and an independently saved recovery ZIP. RECOVERY.md contains the exact saved-file ID, certificate, restore and build commands. Never generate another key for routine builds.

## RC58 build verification, November 17, 2026

171 tests passed with zero failures/errors/skips. Debug and preview assembly and preview vital lint passed. Final APK package app.p2p.interop, version code 94, version 0.7.23-rc58. WebRTC packaging and signature guard passed. The distribution APK was signed using the independently restored saved key. SHA-256: d749d308c25277dfdf2b3974d0e6ff5375545b6c36f73a4b4f56efbe7f076ab2.

## RC59 phone diagnostic build

RC59 (95) retains the established RC58 signing identity. Shared apps now have a Copy app diagnostics button. It reports WebView version, guard status, entry/API serving, frame-load result, renderer/load errors and measured view bounds. JavaScript console errors are reduced to allowlisted error categories and line numbers; raw messages, source URLs, chat data and account addresses are not copied. Events are bounded to 20 unique entries per session and also recorded in the local AppLog.

Ask for the copied diagnostics after opening an affected app and waiting about one minute, plus the tested app name. The black-screen root cause is not confirmed and this release must not be described as a verified fix. Emulator installation succeeded but its software-mode boot did not finish; see EMULATOR-TEST-STATUS.md.


## RC60 runtime changes

User reports RC59 blank screens in Calendar, Calculator, Checklist and other apps. Calendar's current catalog package hides its body until status-update initialization and requestAnimationFrame complete. RC59 proves that entry/API files are served but not that the inner application is visible.

RC60 restores same-origin worker loading (the earlier explicit worker-src blob: incorrectly excluded bundled scripts), explicitly resumes visible WebViews in AndroidView.update even without an Activity transition, and adds an eight-second coroutine timeout around status-read suspension. This timeout can cancel lock/suspension waits but cannot forcibly interrupt a blocking native call. Existing empty-update fallback remains unchanged. Resource diagnostics distinguish favicon/mime type; console categories distinguish CSP from JavaScript errors. Copy diagnostics now adds inner-document visibility, API presence, frame dimensions, script count, and native attachment state, without app text or calendar data.

These are compatibility/stall corrections and diagnostic improvements, not a phone-verified explanation or fix for every reported black screen. Keep this limitation explicit.


## RC61 zero-height frame fix

RC60 phone diagnostics established frameHeight=0 despite initialized API, complete visible body, successful status reads and attached 1080x1886 native WebView. RC61 sets native MATCH_PARENT parameters and replaces the wrapper's 100vh container with a fixed inset viewport container. This is a targeted layout correction; verify positive frame height and usable Calendar, Calculator and Checklist on the phone. Existing RC58 signing key retained.

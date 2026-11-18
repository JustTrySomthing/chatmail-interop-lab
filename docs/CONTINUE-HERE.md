# Continue Relay Lab development

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

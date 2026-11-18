# Delta Chat help parity review

Implementation update: the first five priorities below (leave/unfollow, mute/archive consistency, channel management, profile deletion and local retention) have now been addressed in development source. See [CONTINUE-HERE.md](CONTINUE-HERE.md) for behavior, validation and remaining device tests. The table below preserves the original review findings; it is not a current list of unimplemented tasks.

Reviewed 2026-11-18 against https://delta.chat/en/help and Relay Lab source commit `499bd77ec51abf26b969f72adc02412c0e370629` on securejoin-invites. Published APK: RC74 (110); attachment-menu and button-label changes are newer source awaiting release.

This is a source/API/UI review, not a new device interoperability test or independent security audit. The help page is an overview, not an exhaustive conformance specification. Existing automated tests do not demonstrate complete interoperability.

User priority update: remaining pin-navigation, original-media, initial-video-choice and presence additions are deferred. Prioritize clear unread/missed-event indicators and an obvious in-call video toggle; see CONTINUE-HERE.md for that implementation.

## Actionable findings

| Priority | Finding in Relay Lab | Evidence and next step |
| --- | --- | --- |
| High | No explicit Leave Group action | `NativeMessages.kt:readNativeThread` excludes self contact 1 from editable members. `Chatmail.kt:nativeUpdateGroup` only removes contacts present in that parsed member set. `MainActivity.kt` offers local Trash/archive/block actions but no self-removal route. Add an explicit confirmed Core-backed leave operation; preserve the existing local Trash semantics. |
| High | Muted/archive semantics can diverge | `P2pModel.kt:setThreadPreference` stores mute locally; `Chatmail.kt` has no corresponding chat mute setter. `cacheNativeMessages` clears local Archive when Core reports the chat unarchived. Core therefore cannot apply the local mute preference when deciding whether incoming messages unarchive a chat. Reproduce with a muted archived native chat, then align Core mute state and UI behavior. Also review pin/mute state across linked clients. |
| High | Channel management incomplete | `ChannelBoard.kt:ChannelPostCard` provides save, reactions, attachments and owner view counts, without post edit/delete actions. Channel/profile UI has no explicit unfollow/unsubscribe or delete-channel operation. The existing native message-action UI is for chats/groups, not channel cards. Add appropriate owner and subscriber operations backed by Core, with confirmations and clear local-versus-shared deletion behavior. |
| High | No profile removal control | `ProfileChooser.kt` supports add, edit active, switch and enable/disable. `RelayProfiles.kt` does not expose a profile deletion workflow. Design confirmed local removal with proper shutdown, notification/worker cleanup and storage isolation; removal must not delete another profile or imply immediate relay-side deletion. |
| Medium | No automatic age-based local message cleanup setting | No `delete_device_after` configuration route was found in the app adapter/settings. Disappearing-message controls already exist and are not a replacement for local storage retention. Add an optional per-profile cleanup setting and verify protection of Saved items and personal app instances. |
| Medium | Original-quality selection absent in pending attachment chooser | RC74 still has the old raw-file path. The approved new `AttachmentPicker.kt` uses `describeAttachment(..., asFile=false)` for all picked items, correctly respecting compression but removing a convenient explicit original-media choice. Keep the four-action chooser; consider a per-draft Send Original toggle for media instead of adding a fifth menu entry. Documents and Webxdc packages still pass through unchanged. Do not silently reverse the user's requested default compression behavior. |
| Medium | Shared pins lack convenient navigation | Pin/unpin and Core pin retrieval exist. `MainActivity.kt:native-pinned-messages` renders text and separators; there is no chat-top pin strip, jump-to-message action in that list, or direct app launch from a pinned entry. Complete the navigation rather than inventing a separate local pin system. |
| Medium | Calls always start audio-only | `Chatmail.kt:placeCall` passes false for initial video; `TalkCalls.kt:start` has no initial video choice. Camera toggling, camera switching and PiP already exist. Consider an Audio/Video choice after Make a Call or a contact's call action. |
| Low | Recent activity presentation is partial | `NativeContacts.kt` renders a calendar-date Last seen value; no recent-activity avatar dot was found. This is a presentation gap, not evidence of a broken connection. |
| Low | Delivery/read state is available mainly through Message Info | `NativeMessageDetails` maps queued/failed/sent/read and exposes individual readers. Chat bubbles render Edited/Forwarded/Pinned labels but do not expose the corresponding one/two-tick presentation. Earlier user preferences removed delivery clutter; do not add it back automatically. |
| Review | Silent incoming-call behavior needs checking | The notifications control sets Core `who_can_call_me`; do not assume this is exactly equivalent to suppressing the ringing screen while still allowing acceptance from a chat bubble. Review Core version semantics and run a call test with ringing disabled. |
| Review | Sensitive export/transfer reauthentication | Profile transfer uses a screenshot-protected dialog, but no explicit device-credential/biometric gate was found around transfer QR or standard backup export. Review whether a local authentication gate should be added when a device lock is configured. This is not a claim of a demonstrated exploit. |

## Implemented coverage found in source

- SecureJoin contact/group/channel invitations: QR, link copying/sharing, image scanning and pasted links; native request acceptance and blocking.
- Profile creation, independent storage, name/photo/bio editing, switching, background enable/disable, and notification routing.
- Native text and attachments, quotes, reactions, forwarding, contact-card creation/import, message editing, deletion for everyone, shared message pinning and disappearing timers.
- Saved copies through Core self-chat, global Saved access, source navigation, notes/files, save/unsave controls and separate personal Webxdc instances.
- Group creation, naming, description, member edits, photos and cloning. `P2pModel.kt:cacheNativeThread` sets the UI's owner marker for writable groups; the apparent owner check does not restrict these native controls to the original creator.
- Channels: create/join, descriptions/photos, posting, reactions, owner read counts and shared invites. Subscriber privacy/history delivery depends on Core and still needs cross-client verification.
- Outgoing quality settings, video preparation/trimming, recorded-audio quality and native image handling. The pending chooser preserves these paths.
- Audio/video call signaling through Core, incoming notifications, answer/decline, microphone/speaker controls, camera toggles/switching, missed-call history and supported-device PiP.
- Webxdc package execution, isolation, update/realtime bridges and catalog; fullscreen handling added in RC74.
- Standard Chatmail backup import/export, QR second-device transfer, per-profile read-receipt setting, relay add/list/remove and fingerprint display.
- Local searchable help and Android permissions/background-delivery settings.

## Deliberate or broader-scope differences

- Google/Firebase push is excluded by the user's existing requirements. Relay Lab uses background connections/synchronization and offers reliable foreground delivery with Android's persistent notification. Phone-specific background reliability remains a test requirement; it is not guaranteed merely by enabling setup options.
- No developer-usage-statistics feature was identified. This is not needed for conversation compatibility.
- Relay Lab targets Android 12+ ARM64. Other desktop/mobile ports are separate projects, not small UI omissions.
- No full classic-email account/password/server setup UI or unencrypted-email inbox mode was identified. Relay/login links are supported. The native chat parser requires encrypted conversations. Adding classic unencrypted email would be a product/security-model decision, not an automatic parity fix.
- App desktop layout, Saved placement, the four attachment actions, and contact sharing through Contacts are intentional UI choices. Keep them while completing underlying capabilities.
- The help page's discussion of planned cryptographic work is not evidence that those capabilities are already shipping. Do not label future forward-secrecy or post-quantum work as a Relay-Lab-only missing feature or claim an independent Relay Lab security audit from upstream component audits.

## Verification still required

Use fresh Relay Lab, Delta Chat and ArcaneChat profiles with recorded client versions. Test invites, text/files/reactions, membership changes and leaving, channel joins/history/management, Saved synchronization and survival after source deletion, shared pins, editing/deletion, disappearing messages, backups/device transfer, inactive-profile delivery and calls, Webxdc collaboration, and Wi-Fi/mobile/background transitions.

README contains a user-reported successful Delta Chat call in each direction; it does not establish complete video, background or ArcaneChat parity. No new runtime tests or application changes were performed for this review.

Recommended order: muted/archive consistency and leave/unfollow controls; profile removal and local retention; original-quality draft option and pin navigation; remaining call/presence presentation. Keep separate device-tested results and preserve existing user-approved differences.

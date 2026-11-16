# P2P internet architecture and requirements

## Agreed direction

Build a direct-first, internet-first Android app. Reachable copies of the app can help other phones discover each other while those copies are already running and able to participate. The phones exchange payloads over an authenticated direct connection. This design requires no hosting, paid service, or routine maintenance from the project owner.

Start by proving remote onboarding and delivery. Position the first release according to the measured results. If messaging waits are too frequent for everyday use but remote file sharing proves practical, launch as a private sharing app first. Describe the delivery behavior people can actually expect.

## Product requirements

People in different parts of the country must be able to exchange messages, posts, and large files over their normal internet connection, including home broadband and cellular data. Users install the Android app and add the people they want to reach. They need no hosted account, subscription, companion computer, manual router configuration, or network settings to use the normal flow.

The project must operate without a developer-hosted or maintained service, a paid service, a fixed bootstrap endpoint, a hosted push service, or a required payload relay. An installed Android app may act as a discovery peer while reachable. The default discovery and delivery design uses participating app instances only. The app uses no SMS/MMS transport or carrier messaging permissions.

## What the network can promise

Internet delivery is the primary use case. Direct routes can work over home broadband and cellular, including between distant users. Installing two fresh copies of the app gives those devices no way to discover each other by identity alone. At least one usable contact endpoint or reachable participating app must be known through an invitation or previously learned peer information. This is an availability dependency on the peer network itself. A brand-new network with no reachable participants, or an isolated pair with no direct route, cannot deliver under the current requirements.

The first release keeps the infrastructure constraint. It queues items when no direct path is available and tells users what is waiting. Automatic discovery from a name or identity with zero network hints, delivery while the sending phone is offline, and universal screen-off delivery are outside this delivery contract. If field results show the app-only overlay cannot support normal remote onboarding or everyday delivery at launch, document the gap before any change to the requirements.

## App behavior

| Area | Requirements |
|---|---|
| Shell | Persistent top bar with the current tab title, Settings, and Contacts. Persistent bottom navigation for Chats, Groups, Social, and Files. One contact identity across the app. |
| Chats | OnKlav Messages behavior for Inbox, Archive, Trash, Gallery, Starred, reactions, and unread status. |
| Groups | OnKlav Groups behavior, with independent delivery to members and no app-imposed member cap. Keep concurrency and per-peer queues bounded. Sync membership changes and enforce membership before serving new content. |
| Social | OnKlav Real Social behavior for My Page, Friends, and Feed. Preserve accepted-friend requests, chronological posts, reactions, replies, and the three most recent posts for newly accepted friends. Carry over the 30-day expiry for cached Social media and let people keep a copy through Save to Files. |
| Files | Received, Sent, and Send files. Send APKs, PDFs, documents, and large videos through Files or as file attachments in Chats and Groups. A conversation card and its Files entry point to the same transfer and local copy. Ordinary media appears in Gallery or Social and can be explicitly saved to Files. Support download over Wi-Fi when requested, pause, cancel, and resume. |

## Internet connectivity

1. **Import a contact invitation.** Provide **Show QR**, **Copy invite**, and **Paste invite**. The copyable invitation is a self-contained token that can be passed through any existing channel, or shown in person as a QR code. The app needs no hosted link resolver. The token binds a public identity to a short-lived, unguessable invitation secret and may include recent direct candidates and reachable peer hints. Use a secret with at least 128 bits of randomness. Treat any network hint as untrusted until validated. Confirm that the invitation came from the intended person, then verify the cryptographic identity at connection time. A token expires and can be cancelled by its issuer. Accepting or replaying it must never silently create a trusted contact or consume an invitation before the issuer approves the pairing. When candidates are stale, show **Ask for a fresh invite**.
2. **Join the discovery overlay.** Try a direct contact candidate and use recent hints from the invite or a bounded local peer cache to join an app-specific Kademlia overlay. Independently learned peers can replace failed peers. An app instance serves discovery only while proven reachable and while Android and the device's network and battery conditions permit. Other phones act as clients. Discovery peers serve small, short-lived records and cannot read contact rendezvous content. Do not assume they are always online or that a fresh install already has a peer address.
3. **Coordinate a direct connection.** Attempt direct IPv6 and IPv4 first. Where both parties can reach the overlay, use a private, expiring rendezvous record for candidate exchange and connection timing. Initial invitations derive access to their rendezvous records from the invitation secret. Confirmed contacts use independent per-contact secrets. Use authenticated candidate checks and app-peer address observations, with no required public STUN service. Implement UDP traversal with the same local socket and network path used for the selected QUIC connection. Test direct authenticated TCP when UDP is filtered and a TCP path exists. Treat relay-assisted libp2p hole punching as a different design. Discard stale candidates when Android changes networks, then check fresh paths. Shared-network discovery provides an additional route.
4. **Protect the discovery overlay.** Use purpose-specific record names, authenticated encryption, bounded sizes and lifetimes, and storage and request quotas. Verify candidate records before use and bound connection attempts against untrusted hints. Require end-to-end peer authentication independently of overlay responses. Store no public address book, payloads, or files in the overlay. Direct peers learn each other's network addresses. Discovery peers can observe connection metadata such as source IP addresses and timing.
5. **Queue and recover.** Commit outgoing data before sending. Distinguish an invite waiting for a peer, a queued send, and a transfer in progress. Retry with capped backoff and jitter when conditions change, preserve progress across restarts, and allow user cancellation. Never use online presence as a delivery receipt. Receiving-side authorization, deduplication, and durable acknowledgement apply across retries and concurrent sessions.

### Delivery contract

- **Queued** means the sender saved the item locally. **Delivered** means the recipient saved and acknowledged it. Show file-offer delivery and verified download completion separately. In groups, one offline member does not block other deliveries. For the first release, each author sends directly to authorized recipients. The recipient can catch up when that author is reachable.
- Direct delivery requires overlapping reachability. Some carrier NAT or firewall combinations block every direct route. Android can defer network access when the app is in the background or the device is idle. A recipient may have to reopen the app before it can sync. A phone that is powered off cannot receive or wake on demand.
- Devices keep downloaded content locally for offline access. Pending content stays on the sender until delivery is possible or the sender cancels it. A file transfer can resume only while the source copy is still available. This design offers internet delivery where a direct path is available. A universal instant-delivery or offline-mailbox guarantee would require a separate change to the requirements.

## Implementation

- **Kotlin and Jetpack Compose** for Android UI, navigation, permissions, sharing, notifications, and lifecycle. **Room/SQLite** for durable outbox, contacts, content indexes, and transfer checkpoints.
- **Rust, Tokio, and UniFFI** for discovery, authenticated transport, sync, and file streaming. Use rust-libp2p for candidate Kademlia and transport components. Implement and verify relay-free candidate coordination as a distinct work item. Android Network Service Discovery supports the local path. Use versioned Protocol Buffers at the app protocol boundary.
- **Stable contact identities and device credentials.** Bind verified contacts to their long-term public identity and use authenticated sessions with ephemeral keys. Keep local secrets protected by Android Keystore and support explicit encrypted recovery or phone migration. Avoid uploading application data to automatic cloud backup.
- **Reliable sync and files.** Use stable record IDs and idempotent retries. Commit before acknowledging receipt. Bind the transfer to a content manifest with file size and a cryptographic digest. Stream files with verified chunks, 64-bit offsets, and a final integrity check. Use persistent access to a stable file source or stage one private copy when the picker or share grant cannot survive restart. Detect source changes, insufficient space, and revoked access. Preserve the transfer state and prompt the sender to select the original file again when appropriate. Show completed files only after verification. Opening or exporting an APK must be an explicit action through Android's normal system flow. For user-started large transfers, use Android's supported transfer scheduling and show progress locally.
- **Group membership and social permissions.** Use owner-authorized, versioned membership updates in the first release. Authorize reads and sends against the latest authenticated membership a device has received. Propagate membership updates before later group content and stop serving new content to removed members once the update is applied. Membership changes converge as participants reconnect. A removal cannot erase prior copies or take effect on an isolated device before it learns about the change. Keep local Archive, Trash, and Starred actions local. Apply Social friend acceptance, removal, and blocking when authorizing posts and replies.
- **Protocol compatibility and recovery.** Negotiate supported protocol versions and capabilities before syncing. A new device restores the same identity through migration or an explicit encrypted backup. Contacts verify the new device credential and apply device revocations once they receive them. Keep identity recovery and optional history backup distinct in the UI.

## Development plan

| Phase | Work | Deliverable and exit check |
|---|---|---|
| 1. Remote Android alpha | Set up the Kotlin/Compose app, Rust engine, and Android build. Implement app-only discovery, invite import, identity verification, a minimal chat, and a durable outbox. Add developer-only connection diagnostics with no hosted telemetry. | Install the alpha on two fresh Android devices on independent internet connections in different regions. Exchange an invite, a text message, and a photo. Demonstrate both a direct invite path and discovery through a reachable Android peer. Record failed attempts and verify queued delivery on reconnect. |
| 2. Connection and transfer validation | Expand the network matrix to multiple carriers and independent broadband networks. Exercise screen-off behavior, process restarts, IPv4/IPv6, peer churn, and blocked routes. Add resumable file streaming and verify storage recovery. | Produce a test report with onboarding success, delivery success and latency, queue duration, and connection failure reasons by network and app state. Verify an interrupted 5 GB transfer across independent networks and confirm its final digest. |
| 3. Product buildout | Complete the persistent app shell, Contacts and Settings, then finish Chats and Files on the proven engine. Add Groups and Social with OnKlav behavior, shared contact identity, access controls, and the agreed media and file visibility. | Run end-to-end acceptance tests for all four tabs, including group membership changes, per-recipient delivery, Social friend approval and catch-up, and Gallery, Starred, and Files visibility. |
| 4. Release readiness | Run an invited remote pilot managed by development. Verify upgrade compatibility, recovery, data integrity, background behavior, and real battery and network use. Use pilot results to choose the initial release scope and describe how delivery works. | A signed Android release candidate and a readiness report listing tested conditions, known limits, and supported workflows. Release as a private sharing app if sharing is dependable but messaging delays are common. Claim everyday messaging suitability only where the evidence supports it. |

Phases 1 and 2 should establish internet viability before full feature buildout. The first alpha can use a simple chat screen. Full visual parity with OnKlav belongs in the product buildout. Development owns the device and network testing, including recruiting any remote testers needed. The project owner has no testing or hosting assignment.

### What development measures

- **First remote connection.** Count every fresh-install attempt from importing an invite through verified pairing, first text receipt, and first photo receipt. Report success and time to completion separately. Record whether a direct hint or another app peer enabled discovery.
- **Repeat delivery.** Track time from Send to durable acknowledgement for foreground, screen-off, and reconnect cases. Include messages still queued when the test ends. Record whether reopening the app was required.
- **File sharing.** Track offer delivery, completed downloads, successful resume, and integrity failures separately. Confirm that a user can find the same transfer from its conversation and Files.
- **Infrastructure independence.** Run tests with only participating Android apps available for discovery and transfer. Verify clean failure when no direct path or reachable discovery peer exists. Document any dependencies found during testing.

## Release verification owned by development

The project team runs automated tests and arranges device and carrier coverage. The project owner is not responsible for supplying test phones, testing personally, or running infrastructure.

| Test | Required result |
|---|---|
| Different networks | Development runs actual remote pairings on independent home broadband and cellular connections, covering home-to-home, home-to-cellular, and cellular-to-cellular across multiple carriers. Record all attempts, direct-path success rates, connection times, and failure reasons. Test IPv6, IPv4, UDP filtering, network changes, and screen-off behavior. |
| Bootstrap and peer churn | Test two clean installs using an invite with a reachable direct candidate, and clean installs joining through another Android app instance when the inviter is not directly dialable. Remove the first discovery peer and verify recovery through another app peer or a renewed invite. Also test an empty network, stale invite hints, and zero reachable peers. These cases must fail clearly and preserve queued items. Run without fixed endpoints and block unrelated service traffic. |
| Blocked routes and Android idle | Use simulated restrictive NAT and real Android idle tests. Items remain queued when unreachable, deliver once a usable path returns, and never show false delivery. |
| Large file | Send a 5 GB file across independent networks. Interrupt the transfer, restart the app, and verify resumption and byte-for-byte integrity. |
| Privacy and groups | Reject malformed or replayed control records, unauthorized fetches, and unauthorized requests after a membership change has been received. Test membership removal during a partition, eventual catch-up, identity migration, and lost-device revocation. Confirm independent delivery with unavailable group members and bounded resource use as membership grows. |
| OnKlav parity | Verify Chats and Groups routing, archive and trash behavior, Gallery, and Starred. Verify Social invites, accepted-friend visibility, reactions, replies, and recent-post catch-up. Confirm file cards and Files reuse the same transfer, and ordinary media appears in Files only after an explicit save or send-as-file action. |

Release claims must reflect the measured device and network results. Same-Wi-Fi success alone does not satisfy the internet requirement. Record onboarding success separately from delivery after pairing. A test run that silently excludes failed connection attempts cannot establish launch readiness. State supported scenarios from the actual results.

## Technical references

- [libp2p DHT](https://libp2p.io/docs/dht/), [Kademlia client and server modes](https://github.com/libp2p/specs/tree/master/kad-dht), and [relay-assisted DCUtR](https://libp2p.io/docs/dcutr/)
- [RFC 8445, ICE](https://www.rfc-editor.org/rfc/rfc8445.html) and [RFC 8656, TURN](https://www.rfc-editor.org/rfc/rfc8656.html)
- [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby), [Android user-initiated transfers](https://developer.android.com/develop/background-work/background-tasks/uidt), and [Android file access](https://developer.android.com/training/data-storage/shared/documents-files)

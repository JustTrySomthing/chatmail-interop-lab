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

In Contacts → Add contact, use **Invite someone on Delta Chat or ArcaneChat** to show the standard Chatmail QR code, copy the link, or share it. To use a contact invite from either client, choose **Accept invite**, then scan its QR code, select a saved QR image, or paste the link. If Android opens the link with Relay Lab, the link is prefilled and **Connect** starts the handshake. On Android versions that open the web link in a browser by default, copy the link into Relay Lab.

Use fresh profiles and record the app version and network for each direction. “SecureJoin started” means Chatmail Core accepted the invite and started processing it. Contact and verification status in Relay Lab's chat list, plain encrypted text, and call signaling are pending.

| Client | Relay Lab sends invite | Relay Lab accepts invite |
| --- | --- | --- |
| Delta Chat | Device test pending | Device test pending |
| ArcaneChat | Device test pending | Device test pending |

# rc29 (65): native call initialization repair

rc28 removed org.jni_zero.JniInit and JniUtil during R8 shrinking. WebRTC JNI_OnLoad looks up JniInit by its exact class name. The released rc27 contains these helpers. The ARM64 libjingle_peerconnection_so.so bytes are identical between rc27 and rc28: SHA256 5d68547da3fad7679846187577a0333c10d9ff4d39322bce61f366426924612e.

Repair: disable release code minification and resource shrinking, add explicit org.jni_zero keep rules, and add a signed-APK class/method guard. Preserve package and rc28 signing key. Call routing, ICE wait times, UI, native binaries and voice assets are unchanged.

Also repair native crash report formatting using a bounded parser of Android debuggerd tombstone.proto, selecting the faulting thread rather than decoding binary as UTF-8. Reference: https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto

Physical Motorola/Samsung calling cannot be executed in this workspace. Automated tests and APK inspection are not a two-device native call test.

Checks: 98 automated tests passed (zero failures/errors), including native-crash protobuf formatting. Packaged DEX bootstrap check fails on rc28 and passes on rc27 and rc29. Release APK signature verified against the rc28 certificate.

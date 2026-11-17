# Android emulator / Webxdc runtime test status

November 17, 2026. Emulator installation succeeded; the black-screen runtime test did not complete.

Installed official Linux x86_64 Android Emulator 37.2.13 (build 16740729) and Google APIs Android 15 / API 35 x86_64 image revision 9. Verified both archive checksums against Android repository metadata. The image advertises x86_64 and arm64-v8a and libndk_translation.so. This is image capability metadata, not evidence that Relay Lab has run successfully.

Host: x86_64; /dev/kvm unavailable; emulator acceleration check reports no vmx/svm support. Used software CPU emulation and SwiftShader graphics. Created a separate disposable RelayLab35 AVD (480x800, 2 virtual CPUs, 2 GiB RAM).

## Observed results

- Emulator executable runs; Android kernel, init, zygote and system_server start.
- ADB reaches online state and returns the guest ABI list.
- An early install of a small diagnostic APK failed because Android's package service was not yet available.
- Final bounded attempt made 24 boot checks approximately 20 seconds apart (about eight minutes, plus command overhead). sys.boot_completed never became 1; result BOOT_NOT_COMPLETE, exit code 2.
- Final logcat showed ART/dex2oat still verifying/preparing system applications. No demonstrated permanent boot failure was established. Software emulation may need longer or a more suitable host.
- Relay Lab was not launched. Its shared-app black-screen cause remains unconfirmed. Do not count emulator installation, ADB connectivity, or this boot attempt as passing WebView/device testing.

## Reproduce installation and boot

Set ANDROID_HOME to a writable Android SDK directory, then run:

```sh
python3 scripts/install-test-emulator.py
export ANDROID_AVD_HOME=/path/to/disposable-test-avds
# Use auto on hosts with acceleration; off is the slow fallback used here.
export RELAY_EMULATOR_ACCEL=off
bash scripts/start-test-emulator.sh
```

Current SDK: /workspace/scratch/a3938b45b30b/sdk
Current AVD directory: /workspace/scratch/ac158501f7e8/android-avd
Current logs: /workspace/scratch/ac158501f7e8/emulator-final.log and emulator-final-logcat.txt.
Temporary diagnostic harness: /workspace/scratch/ac158501f7e8/webxdc-probe. It copies production Webxdc HTML/JavaScript assets into a separate Java WebView host with a stub bridge; it does not exercise the production Compose layout, account, or messaging. It compiled but did not run. Do not claim otherwise.

The setup scripts in GitHub are reproducible; local paths are workspace checkpoints, not durable backup locations. Existing source and RC58 signing recovery remain in RECOVERY.md. No production app code, signing identity or published APK changed during this task.

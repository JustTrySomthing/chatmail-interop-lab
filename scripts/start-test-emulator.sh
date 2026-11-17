#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the SDK containing emulator and system-images/android-35/google_apis/x86_64}"
: "${ANDROID_AVD_HOME:?Set ANDROID_AVD_HOME to a writable test-only AVD directory}"
mkdir -p "$ANDROID_AVD_HOME/RelayLab35.avd"
cat > "$ANDROID_AVD_HOME/RelayLab35.ini" <<INI
avd.ini.encoding=UTF-8
path=$ANDROID_AVD_HOME/RelayLab35.avd
target=android-35
INI
if [[ ! -f "$ANDROID_AVD_HOME/RelayLab35.avd/config.ini" ]]; then
cat > "$ANDROID_AVD_HOME/RelayLab35.avd/config.ini" <<'INI'
avd.ini.encoding=UTF-8
AvdId=RelayLab35
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=480
hw.lcd.height=800
hw.lcd.density=160
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.audioInput=no
hw.camera.back=none
hw.camera.front=none
disk.dataPartition.size=2G
image.sysdir.1=system-images/android-35/google_apis/x86_64/
tag.id=google_apis
PlayStore.enabled=false
showDeviceFrame=no
fastboot.forceColdBoot=yes
INI
fi
exec "$ANDROID_HOME/emulator/emulator" -avd RelayLab35 -accel "${RELAY_EMULATOR_ACCEL:-auto}" \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    -cores 2 -memory 2048 -port 5554 -show-kernel "$@"

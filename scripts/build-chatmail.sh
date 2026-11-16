#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${android_sdk:?Set ANDROID_HOME to the Android SDK}"
ndk_root=${ANDROID_NDK_HOME:-"$android_sdk/ndk/27.2.12479018"}
ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
version=2.69.0
checksum=6c3b69fba5422e2bc0338e00229547ce66ad8f8892faec802cfa36d8949277c2
output="$project_dir/app/build/chatmailJniLibs"
archive="${P2P_CHATMAIL_APK:-$project_dir/app/build/chatmail-$version.apk}"

mkdir -p "$output"
if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 3 \
        "https://github.com/deltachat/deltachat-android/releases/download/v$version/deltachat-gplay-release-$version.apk" \
        --output "$archive.part"
    printf '%s  %s\n' "$checksum" "$archive.part" | sha256sum --check
    mv "$archive.part" "$archive"
fi
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check

for entry in "arm64-v8a:aarch64-linux-android" "x86_64:x86_64-linux-android"; do
    abi=${entry%%:*}
    target=${entry#*:}
    directory="$output/$abi"
    mkdir -p "$directory"
    unzip -p "$archive" "lib/$abi/libnative-utils.so" > "$directory/libnative-utils.so"
    "$ndk_bin/$target"31-clang -shared -fPIC -Wl,--no-undefined,-z,max-page-size=16384 \
        -Wl,-soname,libp2p_chatmail.so -L"$directory" -o "$directory/libp2p_chatmail.so" \
        "$project_dir/app/src/main/cpp/chatmail_jni.c" -lnative-utils
done

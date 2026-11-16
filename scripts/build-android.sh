#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${android_sdk:?Set ANDROID_HOME to the Android SDK}"
ndk_root=${ANDROID_NDK_HOME:-"$android_sdk/ndk/27.2.12479018"}
ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
cargo_command=${P2P_CARGO:-cargo}
gradle_command=${P2P_GRADLE:-"$project_dir/gradlew"}

if [[ ! -x "$ndk_bin/llvm-strip" ]]; then
    printf '%s\n' "Install Android NDK 27.2.12479018 before building." >&2
    exit 1
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android31-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk_bin/x86_64-linux-android31-clang"
export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
export AR_x86_64_linux_android="$ndk_bin/llvm-ar"
export CARGO_TARGET_DIR="$project_dir/engine/target"

"$cargo_command" build --manifest-path "$project_dir/engine/Cargo.toml" --locked \
    --target aarch64-linux-android --target x86_64-linux-android

for entry in "arm64-v8a:aarch64-linux-android" "x86_64:x86_64-linux-android"; do
    abi=${entry%%:*}
    target=${entry#*:}
    native_dir="$project_dir/app/build/rustJniLibs/$abi"
    mkdir -p "$native_dir"
    "$ndk_bin/llvm-strip" --strip-unneeded \
        -o "$native_dir/libp2p_engine.so" \
        "$CARGO_TARGET_DIR/$target/debug/libp2p_engine.so"
done

"$project_dir/scripts/build-chatmail.sh"

"$gradle_command" --project-dir "$project_dir" :app:assembleDebug "$@"

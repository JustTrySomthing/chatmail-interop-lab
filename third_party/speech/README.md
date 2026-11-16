# Bundled read-aloud engine

P2P reuses the ARM64 binaries, compact English voice pack, and corresponding
bridge and dependency sources from OnKlav 0.101.49. The Kotlin JNI class keeps
the OnKlav package name so the same native binary can be used. The two included
Kitten Nano 0.8 FP32 voices use the original speaker IDs 2 (Male) and 0 (Female).

The APK includes the voice pack and manifest under `app/src/main/assets/speech`,
and the native libraries under `app/src/main/jniLibs/arm64-v8a`. The original
OnKlav build recipes and provenance manifests are retained here for reference.
Their paths refer to the OnKlav tree. In this checkout `bridge` is the original
`core/speech` crate.

The Android bridge can be rebuilt from this checkout with Rust's
`aarch64-linux-android` target and Android NDK 27.2.12479018. From the P2P root:

```sh
ndk_bin="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android29-clang"
export SHERPA_ONNX_LIB_DIR="$(pwd)/app/src/main/jniLibs/arm64-v8a"
RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" cargo build --release --locked   --manifest-path third_party/speech/bridge/Cargo.toml --target aarch64-linux-android
```

The Sherpa-ONNX 1.13.8 source and its pinned eSpeak NG and Piper source archives
are included here. See `sources.json` and `speech-dependencies.json` for upstream
source and binary provenance. The Sherpa source archive contains its build
recipes. For Android use API 29, ARM64, shared libraries, C API and TTS enabled,
PortAudio disabled, and 16 KiB page alignment. The included binaries are the
original pinned prebuilts. The model archive and per-file hashes are recorded in
the asset manifest. The compact-model recipe is `build_compact_speech.py`.

Sherpa-ONNX and Kitten Nano use Apache 2.0, ONNX Runtime and Piper phonemizer use
MIT, and eSpeak NG and its pronunciation data use GPL-3.0-or-later. The linked
phonemizer distribution is subject to the GPL. See the notices included in
`app/src/main/assets/licenses`.

# Android toolchain

Verified on this machine; every component installs **without root**.

| Piece | Location | Version |
|---|---|---|
| JDK (Temurin) | `~/.local/jdk21` | 21.0.13 |
| Android SDK | `~/Android/Sdk` | platform-tools 37.0.1, platforms;android-34, build-tools;34.0.0 |
| NDK | `~/Android/Sdk/ndk/27.0.12077973` | r27 |
| rustup + toolchain | `~/.local/rustup`, `~/.local/cargo` | rustc 1.100.0 |

`. scripts/android-env.sh` before any cross build.

Why rustup at all: the distro `rust` package ships only the host standard library. The
`aarch64-linux-android`, `armv7-linux-androideabi` and `x86_64-linux-android` targets come from the
rustup-managed toolchain.

## Verified cross builds

    . scripts/android-env.sh
    cargo build -p herdr-protocol --target aarch64-linux-android   # ok (8 s)
    cargo build -p herdr-client   --target aarch64-linux-android   # ok (8 s)

Both crates compile for Android, which de-risks the JNI core: the socket client is not Linux-only.

## Not done here

iOS cannot be built on this machine: it needs macOS and Xcode. The core stays UniFFI-shaped so an iOS
UI can consume it later, but the target cannot be produced on Linux.

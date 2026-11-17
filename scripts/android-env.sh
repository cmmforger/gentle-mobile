#!/usr/bin/env bash
# Android toolchain for this workspace. Source it, do not execute it:
#   . scripts/android-env.sh
#
# Everything here is installed without root:
#   JDK 21 (Temurin)  -> ~/.local/jdk21
#   Android SDK + NDK -> ~/Android/Sdk (ndk 27.0.12077973)
#   rustup + targets  -> ~/.local/rustup, ~/.local/cargo
#
# The distro `rust` package ships only the host std, which is why the Android
# targets come from a rustup-managed toolchain instead.

export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.local/rustup}"
export CARGO_HOME="${CARGO_HOME:-$HOME/.local/cargo}"
export PATH="$CARGO_HOME/bin:$PATH"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.0.12077973}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk21}"
export PATH="$JAVA_HOME/bin:$PATH"

_ndkbin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER:-$_ndkbin/aarch64-linux-android24-clang}"
export CC_aarch64_linux_android="${CC_aarch64_linux_android:-$_ndkbin/aarch64-linux-android24-clang}"
export AR_aarch64_linux_android="${AR_aarch64_linux_android:-$_ndkbin/llvm-ar}"

export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="${CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER:-$_ndkbin/armv7a-linux-androideabi24-clang}"
export CC_armv7_linux_androideabi="${CC_armv7_linux_androideabi:-$_ndkbin/armv7a-linux-androideabi24-clang}"
export AR_armv7_linux_androideabi="${AR_armv7_linux_androideabi:-$_ndkbin/llvm-ar}"

export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="${CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER:-$_ndkbin/x86_64-linux-android24-clang}"
export CC_x86_64_linux_android="${CC_x86_64_linux_android:-$_ndkbin/x86_64-linux-android24-clang}"
export AR_x86_64_linux_android="${AR_x86_64_linux_android:-$_ndkbin/llvm-ar}"

unset _ndkbin

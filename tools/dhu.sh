#!/usr/bin/env bash
# Opens Google's Desktop Head Unit (a fake Android Auto car screen) on this computer.
# Before: on the phone, Android Auto developer settings -> "Start head unit server".
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/opt/android-sdk-linux}"
NDK="${ANDROID_NDK:-$HOME/opt/android-ndk-r26d}"
DHU="$SDK/extras/google/auto"
# The DHU needs LLVM libc++; the NDK ships a desktop copy, so nothing to install system-wide.
LIBCXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/x86_64-unknown-linux-gnu"

"$SDK/platform-tools/adb" forward tcp:5277 tcp:5277
cd "$DHU"
LD_LIBRARY_PATH="$LIBCXX:$DHU${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  exec ./desktop-head-unit --config="${1:-config/default_720p.ini}"

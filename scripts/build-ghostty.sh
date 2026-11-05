#!/usr/bin/env bash
# Builds static libghostty-vt from the pinned submodule for each Android ABI.
# Output goes to native-out/<abi>/{lib,include}. Needs Zig 0.17 and the NDK.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$sdk/ndk/27.1.12297006}"
commit="$(git -C "$root/third_party/ghostty" rev-parse HEAD)"
build() {
  local abi="$1" target="$2"
  local out="$root/native-out/$abi"
  if [[ -f "$out/.commit" && "$(cat "$out/.commit")" == "$commit" ]]; then return; fi
  (cd "$root/third_party/ghostty" && zig build -Demit-lib-vt=true -Doptimize=ReleaseFast \
    -Dtarget="$target" --prefix "$out")
  echo "$commit" > "$out/.commit"
}
build arm64-v8a aarch64-linux-android.34
build x86_64 x86_64-linux-android.34

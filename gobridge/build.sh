#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Builds gobridge/build/gobridge.aar with gomobile (BUILD_PLAN.md P3.1).
#
# The AAR is rebuilt only when the Go sources or dependencies changed: a hash of
# gobridge/** (minus build/) is kept next to the AAR, so CI's cache and local builds
# both skip the bind when nothing changed. `./build.sh --force` rebuilds anyway.
#
# Needs: Go (the version in go.mod, fetched automatically), gomobile on PATH
# (go install golang.org/x/mobile/cmd/gomobile@latest), and an Android NDK at
# $ANDROID_NDK_HOME or under $ANDROID_HOME/ndk.
set -euo pipefail
cd "$(dirname "$0")"

out=build/gobridge.aar
stamp=build/gobridge.aar.sha256
targets="${GOBRIDGE_TARGETS:-android/arm64,android/amd64,android/arm}"

hash=$(find go.mod go.sum gm wa ig sig libsignal/VERSION build.sh -type f ! -path 'build/*' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
if [ "${1:-}" != "--force" ] && [ -f "$out" ] && [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$hash" ]; then
  echo "gobridge.aar is up to date"
  exit 0
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ANDROID_NDK_HOME is not set and no NDK was found under ANDROID_HOME" >&2
  exit 1
fi
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
# gomobile is installed into GOPATH/bin, which Gradle's environment may not have on PATH.
export PATH="$PATH:$(go env GOPATH)/bin"

# Signal's native library (Rust), built once per version by the libsignal workflow and
# attached to the release "libsignal-<version>": one file per phone chip, and a Linux
# one for the host tests. Downloaded here when missing (gh is signed in locally; CI
# passes GH_TOKEN).
libsignal_version=$(cat libsignal/VERSION)
fetch_libsignal() {
  local abi="$1" target="libsignal/$1/libsignal_ffi.a"
  if [ -f "$target" ]; then return; fi
  echo "Fetching Signal's native library for $abi ($libsignal_version)"
  mkdir -p "libsignal/$abi"
  gh release download "libsignal-$libsignal_version" --pattern "libsignal_ffi-$abi.a" --output "$target" --repo duraleigh/PingMe
}
for abi in arm64-v8a x86_64 armeabi-v7a; do fetch_libsignal "$abi"; done
# The host copy only serves the host checks; a machine that skips them can do without it.
if fetch_libsignal host-linux-amd64; then
  mkdir -p libsignal/host && cp libsignal/host-linux-amd64/libsignal_ffi.a libsignal/host/
elif [ "${GOBRIDGE_SKIP_HOST_CHECKS:-0}" != 1 ]; then
  echo "Signal's native library for the host checks is missing" >&2
  exit 1
fi

# GOBRIDGE_HOST_TAGS: build tags for the host checks ("hostclang" when CC is clang or zig).
# GOBRIDGE_SKIP_HOST_CHECKS=1 skips them on a machine without a host C toolchain and zlib
# (the Signal package's tests link Signal's library); CI never sets it.
if [ "${GOBRIDGE_SKIP_HOST_CHECKS:-0}" = 1 ]; then
  echo "Skipping the Go bridge's host checks (GOBRIDGE_SKIP_HOST_CHECKS=1)"
else
  echo "Checking the Go bridge"
  go vet -tags "${GOBRIDGE_HOST_TAGS:-}" ./...
  go test -tags "${GOBRIDGE_HOST_TAGS:-}" ./...
fi

echo "Binding the Go bridge for $targets (first build: several minutes)"
mkdir -p build
gomobile bind -target "$targets" -androidapi 29 -javapkg org.pingme.gobridge -o "$out" ./gm ./wa ./ig ./sig
echo "$hash" > "$stamp"
ls -l "$out"

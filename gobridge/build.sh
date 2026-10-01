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

hash=$(find go.mod go.sum gm build.sh -type f ! -path 'build/*' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
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

echo "Checking the Go bridge"
go vet ./...
go test ./...

echo "Binding the Go bridge for $targets (first build: several minutes)"
mkdir -p build
gomobile bind -target "$targets" -androidapi 29 -javapkg org.pingme.gobridge -o "$out" ./gm
echo "$hash" > "$stamp"
ls -l "$out"

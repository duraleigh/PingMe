#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Cloud environment setup for PingMe builder sessions.
# Runs at the start of every cloud session through .claude/hooks/session-start.sh,
# so it must be quick when everything is already installed. It can also be pasted
# into the environment's "Setup script" field, but that is not needed.
# Cloud machines already have OpenJDK 21, Gradle, and Go. This adds the Android
# SDK, NDK, and gomobile. It always exits 0 so a slow download never blocks a session.
export ANDROID_HOME=/opt/android-sdk
NDK_VERSION=27.3.13750724
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q -o cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && rm -rf "$ANDROID_HOME/cmdline-tools/latest" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# compileSdk/targetSdk 37.2; build-tools 36.0.0 is AGP 9.4's default, 37.0.0 the newest.
if [ ! -d "$ANDROID_HOME/platforms/android-37.2" ] || [ ! -d "$ANDROID_HOME/build-tools/36.0.0" ] \
    || [ ! -d "$ANDROID_HOME/build-tools/37.0.0" ] || [ ! -d "$ANDROID_HOME/platform-tools" ]; then
  yes | "$SDKM" --licenses >/dev/null 2>&1 || true
  "$SDKM" "platform-tools" "platforms;android-37.2" "build-tools;36.0.0" "build-tools;37.0.0" >/dev/null 2>&1 || true
fi
if [ ! -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
  ( "$SDKM" "ndk;$NDK_VERSION" >/dev/null 2>&1 ) || true &
fi
if [ ! -x "$(go env GOPATH)/bin/gomobile" ]; then
  ( go install golang.org/x/mobile/cmd/gomobile@latest && "$(go env GOPATH)/bin/gomobile" init ) || true &
fi
wait
[ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ] && ln -sfn "$ANDROID_HOME/ndk/$NDK_VERSION" /opt/android-ndk

# Maven Central answers cloud containers with HTTP 429 (rate limit). Point Gradle at
# Google's public mirror of Central. This file lives in ~/.gradle, outside the repo,
# so CI and local machines are unaffected.
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/central-mirror.init.gradle.kts <<'MIRROR'
// Written by scripts/setup-cloud.sh for cloud sessions only.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"
fun RepositoryHandler.redirectCentral() {
    all {
        if (this is MavenArtifactRepository && url.toString().trimEnd('/') in setOf(
                "https://repo.maven.apache.org/maven2", "https://repo1.maven.org/maven2")) {
            setUrl(centralMirror)
        }
    }
}
beforeSettings {
    pluginManagement.repositories.redirectCentral()
    dependencyResolutionManagement.repositories.redirectCentral()
}
MIRROR

if ! grep -q 'ANDROID_HOME=/opt/android-sdk' ~/.bashrc 2>/dev/null; then
  cat >> ~/.bashrc <<'PROFILE'
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-ndk
export PATH="$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$(go env GOPATH)/bin"
PROFILE
fi
exit 0

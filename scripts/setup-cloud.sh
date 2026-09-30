#!/bin/bash
# Cloud environment setup for PingMe builder sessions.
# The owner pastes this into the cloud environment's "Setup script" field.
# Cloud machines already have OpenJDK 21, Gradle, and Go. This adds the Android
# SDK, NDK, and gomobile. It always exits 0 so a slow download never blocks a session.
export ANDROID_HOME=/opt/android-sdk
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q -o cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && rm -rf "$ANDROID_HOME/cmdline-tools/latest" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
# compileSdk/targetSdk 37.2; build-tools 36.0.0 is AGP 9.4's default, 37.0.0 the newest.
"$SDKM" "platform-tools" "platforms;android-37.2" "build-tools;36.0.0" "build-tools;37.0.0" >/dev/null 2>&1 || true
( "$SDKM" "ndk;27.3.13750724" >/dev/null 2>&1 && ln -sfn "$ANDROID_HOME/ndk/27.3.13750724" /opt/android-ndk ) || true &
( go install golang.org/x/mobile/cmd/gomobile@latest && "$(go env GOPATH)/bin/gomobile" init ) || true &
wait
cat >> ~/.bashrc <<'PROFILE'
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-ndk
export PATH="$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$(go env GOPATH)/bin"
PROFILE
exit 0

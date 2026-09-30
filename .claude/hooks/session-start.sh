#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Installs the Android build tools at the start of every cloud session, so the
# environment's own "Setup script" field is not needed. Does nothing on local machines.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# The setup script's output is noise for the session; keep it off stdout.
bash "$CLAUDE_PROJECT_DIR/scripts/setup-cloud.sh" >&2

# Make the tools visible to every command in this session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  cat >> "$CLAUDE_ENV_FILE" <<ENV
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-ndk
export PATH="\$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$(go env GOPATH)/bin"
ENV
fi

#!/usr/bin/env bash
# Canonical local install path for the Homework Buddy tablet.
set -euo pipefail

readonly DEVICE_SERIAL="54833864"
readonly APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

cd "$(dirname "$0")/.."

gradle :app:assembleDebug
adb -s "$DEVICE_SERIAL" wait-for-device
adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"

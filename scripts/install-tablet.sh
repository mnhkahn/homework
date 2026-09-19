#!/usr/bin/env bash
# Canonical local install path for the Homework Buddy tablet.
set -euo pipefail

readonly DEVICE_SERIAL="54833864"
readonly APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

cd "$(dirname "$0")/.."

gradle :app:assembleDebug
adb -s "$DEVICE_SERIAL" wait-for-device
adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"
adb -s "$DEVICE_SERIAL" shell pm grant com.homeworkbuddy android.permission.WRITE_SECURE_SETTINGS
# Apply device-wide settings without bringing the app in front of the user.
adb -s "$DEVICE_SERIAL" shell am broadcast -n com.homeworkbuddy/.KioskSystemReceiver -a com.homeworkbuddy.action.SYNC

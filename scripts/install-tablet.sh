#!/usr/bin/env bash
# Canonical local install path for the Homework Buddy tablet.
set -euo pipefail

readonly DEVICE_SERIAL="54833864"
readonly APK_VARIANT="${HOMEWORK_APK_VARIANT:-debug}"
if [[ "$APK_VARIANT" != "debug" && "$APK_VARIANT" != "release" ]]; then
  echo "HOMEWORK_APK_VARIANT must be debug or release" >&2
  exit 1
fi
readonly APK_PATH="app/build/outputs/apk/${APK_VARIANT}/app-${APK_VARIANT}.apk"

cd "$(dirname "$0")/.."

if [[ "${HOMEWORK_TEST_COPY:-0}" == "1" ]]; then
  [[ "$APK_VARIANT" == "debug" ]] || { echo "Test copy requires debug variant" >&2; exit 1; }
  test_google_services="app/src/debug/google-services.json"
  [[ ! -e "$test_google_services" ]] || { echo "Debug Google configuration already exists" >&2; exit 1; }
  mkdir -p app/src/debug
  trap 'rm -f "$test_google_services"; rmdir app/src/debug 2>/dev/null || true' EXIT
  python3 - "$test_google_services" <<'PY'
import json, sys
with open('app/google-services.json') as f:
    config = json.load(f)
for client in config['client']:
    client['client_info']['android_client_info']['package_name'] = 'com.homeworkbuddy.testcopy'
with open(sys.argv[1], 'w') as f:
    json.dump(config, f)
PY
fi

# A bridge build can retain the tablet's debug signer while carrying the
# release version name, so it upgrades an enrolled Device Owner in place.
if [[ "$APK_VARIANT" == "release" ]]; then
  : "${HOMEWORK_RELEASE_STORE_FILE:?Release keystore path is required}"
  : "${HOMEWORK_RELEASE_STORE_PASSWORD:?Release keystore password is required}"
  gradle_args=(:app:assembleRelease)
else
  gradle_args=(:app:assembleDebug)
fi
if [[ "${HOMEWORK_TEST_COPY:-0}" == "1" ]]; then
  gradle_args+=("-PhomeworkApplicationId=com.homeworkbuddy.testcopy")
fi
if [[ -n "${HOMEWORK_VERSION_NAME:-}" ]]; then
  gradle_args+=("-PreleaseVersionName=${HOMEWORK_VERSION_NAME}")
fi
if [[ -n "${HOMEWORK_VERSION_CODE:-}" ]]; then
  gradle_args+=("-PreleaseVersionCode=${HOMEWORK_VERSION_CODE}")
fi
gradle "${gradle_args[@]}"
adb -s "$DEVICE_SERIAL" wait-for-device
adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"
if [[ "${HOMEWORK_TEST_COPY:-0}" == "1" ]]; then
  exit 0
fi
adb -s "$DEVICE_SERIAL" shell pm grant com.homeworkbuddy android.permission.WRITE_SECURE_SETTINGS
# Apply device-wide settings without bringing the app in front of the user.
adb -s "$DEVICE_SERIAL" shell am broadcast -n com.homeworkbuddy/.KioskSystemReceiver -a com.homeworkbuddy.action.SYNC

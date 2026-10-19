#!/usr/bin/env bash
set -euo pipefail

reports="${RUNNER_TEMP}/flowscope-sample-reports"
mkdir -p "$reports"

./examples/sample-app/gradlew -p examples/sample-app \
  :app:installDebug -PuseMavenLocal=true --refresh-dependencies --stacktrace

serial="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
if [[ -z "$serial" ]]; then
  echo "No booted Android emulator was found" >&2
  exit 1
fi

cli="./recorder-host/build/install/flowscope/bin/flowscope"
if ! "$cli" suite run flows/suites/sample.suite \
  --project examples --serial "$serial" --output "$reports"; then
  package_uid="$(adb -s "$serial" shell cmd package list packages -U \
    io.github.flowscopedev.sample \
    | sed -n 's/.* uid:\([0-9][0-9]*\).*/\1/p' | head -n 1 | tr -d '\r')"
  if [[ -n "$package_uid" ]]; then
    adb -s "$serial" logcat -d --uid="$package_uid" \
      > "${RUNNER_TEMP}/sample-suite-logcat.txt" || true
  fi
  exit 1
fi

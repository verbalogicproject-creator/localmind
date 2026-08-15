#!/usr/bin/env bash
#
# Runs INSIDE a booted emulator (invoked by android-emulator-runner's `script:`).
#
# The important design choice here is that the launch smoke test runs against the
# RELEASE build, not the debug one. Debug builds skip R8 entirely, so a debug launch
# proves nothing about the artifact users actually install. The failure this exists
# to catch -- R8 stripping a kotlinx.serialization serializer -- is invisible in
# every debug build and in every JVM unit test.
#
set -uo pipefail

PKG={{APPLICATION_ID}}
LAUNCHER="$PKG/.MainActivity"
OUT=emulator-out
mkdir -p "$OUT"
FAILED=0

fail() { echo "::error::$1"; FAILED=1; }

echo "=============================================================="
echo " 1. decode the signing key (needed before ANY release task)"
echo "=============================================================="
if [ -n "${SIGNING_KEY_BASE64:-}" ]; then
  printf '%s' "$SIGNING_KEY_BASE64" | base64 -d > "$SIGNING_KEYSTORE_PATH"
  sz=$(stat -c '%s' "$SIGNING_KEYSTORE_PATH")
  if [ "$sz" -lt 1024 ]; then
    fail "decoded keystore is $sz bytes -- secret corrupt or truncated"
  else
    echo "keystore decoded ($sz bytes)"
  fi
else
  echo "no signing secrets; the release variant will be unsigned and uninstallable"
fi

echo
echo "=============================================================="
echo " 2. instrumented tests (debug variant)"
echo "=============================================================="
# Debug on purpose. connectedReleaseAndroidTest was tried and hangs indefinitely
# with no output (run 31875004036, both API levels, 45-minute timeout), so these
# tests cover Hilt graph construction, Room migration and the Compose tree -- and
# explicitly do NOT cover minification. R8 is covered by the mapping.txt assertion
# in ci.yml and by the release launch smoke in step 4 below.
./gradlew connectedDebugAndroidTest --stacktrace 2>&1 | tee "$OUT/instrumented.log" \
  || fail "instrumented tests failed"

echo
echo "=============================================================="
echo " 3. the artifact users actually install"
echo "=============================================================="
./gradlew assembleRelease --stacktrace 2>&1 | tee "$OUT/release-build.log" \
  || fail "release build failed"

APK=$(find app/build/outputs/apk/release -name '*.apk' ! -name '*unsigned*' | head -1)
if [ -z "$APK" ]; then
  echo "no signed release APK; skipping the launch smoke test"
  # Not a failure here -- release.yml is what enforces signing. This job's unique
  # value is the launch, and an unsigned APK cannot be installed to attempt it.
  rm -f "$SIGNING_KEYSTORE_PATH" 2>/dev/null || true
  exit "$FAILED"
fi

echo
echo "=============================================================="
echo " 4. install and launch the RELEASE build"
echo "=============================================================="
# connectedReleaseAndroidTest already installed a build; remove it so this is a
# clean install rather than an implicit upgrade.
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$APK" 2>&1 | tee "$OUT/install.log" || fail "release APK would not install"
adb logcat -c

adb shell am start -W -n "$LAUNCHER" 2>&1 | tee "$OUT/launch.log"

# Six seconds is well past first composition. A crash on the Hilt graph or on font
# resolution happens in the first few hundred milliseconds.
sleep 6

if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
  fail "the release build died within 6s of launch -- it does not run"
fi

adb logcat -d -b crash > "$OUT/crash-buffer.log" 2>/dev/null || true
if [ -s "$OUT/crash-buffer.log" ]; then
  echo "--- crash buffer ---"
  cat "$OUT/crash-buffer.log"
  fail "FATAL exception in the release build"
fi

# The app writes its own crash file because logcat is unreadable on the target
# device. If it exists, the handler fired.
adb shell "run-as $PKG cat files/crash.txt" > "$OUT/crash.txt" 2>/dev/null || true
if [ -s "$OUT/crash.txt" ]; then
  echo "--- app-reported crash ---"
  cat "$OUT/crash.txt"
  fail "app wrote a crash.txt during launch"
fi

echo
echo "=============================================================="
echo " 5. upgrade continuity -- does this install OVER the last release?"
echo "=============================================================="
# The failure with no recovery path. If the signing key changes, no installed device
# will ever accept an update: Android rejects it with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the entire installed cohort is orphaned
# permanently. In the predecessor project every CI "release" was signed with the
# runner's auto-generated debug keystore -- a different key each run -- and nothing
# anywhere reported it.
#
# The cert-digest pin in release.yml catches this deterministically and earlier. This
# is the empirical backstop: it asks Android itself.
PREV=$(gh release list --limit 10 --json tagName --jq '.[].tagName' 2>/dev/null \
       | grep -v "^${GITHUB_REF_NAME:-}$" | head -1)

# versionCode of both artifacts. An untagged branch build carries the local default
# (1), so installing it over a later release is a DOWNGRADE, not a signing failure.
# Running the check anyway produces a red build blaming the wrong cause -- which is
# exactly what happened on conformance/red-runtime, where this step reported
# "Signing key drift orphans every install" for what was a version downgrade.
vcode() { "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1; }
AAPT=$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}/build-tools" -name aapt2 2>/dev/null | sort -V | tail -1)

if [ -z "$PREV" ]; then
  echo "no previous release to upgrade from; skipping (this is the first)"
elif [ -z "$AAPT" ]; then
  echo "aapt2 not found; skipping the upgrade check rather than guessing"
else
  echo "previous release: $PREV"
  rm -rf /tmp/prev && mkdir -p /tmp/prev
  if ! gh release download "$PREV" -p '*.apk' -D /tmp/prev 2>/dev/null; then
    echo "could not download $PREV; skipping the upgrade check"
  else
    PREV_APK=$(find /tmp/prev -name '*.apk' | head -1)
    NEW_VC=$(vcode "$APK"); PREV_VC=$(vcode "$PREV_APK")
    echo "versionCode: $PREV=$PREV_VC  current=$NEW_VC"

    if [ -n "$NEW_VC" ] && [ -n "$PREV_VC" ] && [ "$NEW_VC" -lt "$PREV_VC" ]; then
      # Real and expected on any untagged build. Not a defect, and saying nothing
      # would be as bad as blaming the wrong thing.
      echo "SKIP: current versionCode ($NEW_VC) is older than $PREV ($PREV_VC)."
      echo "      Untagged builds carry the local default. Upgrade continuity is"
      echo "      only meaningful for a tagged release, where versionCode derives"
      echo "      from the tag."
    else
      adb uninstall "$PKG" >/dev/null 2>&1 || true
      if ! adb install "$PREV_APK" 2>&1 | tee "$OUT/install-prev.log" | grep -q 'Success'; then
        echo "could not install $PREV; skipping the upgrade check"
      elif adb install -r "$APK" 2>&1 | tee "$OUT/upgrade.log" | grep -q 'Success'; then
        echo "upgrade OK: $PREV -> ${GITHUB_REF_NAME:-current}"
      else
        cat "$OUT/upgrade.log"
        # Name the cause Android reported. Do NOT assert a diagnosis the evidence
        # does not support -- a check that blames the wrong thing sends you
        # debugging something that is not broken.
        if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "$OUT/upgrade.log"; then
          fail "SIGNING KEY DRIFT: $PREV and this build have different certificates. Every installed device is orphaned permanently."
        elif grep -q 'INSTALL_FAILED_VERSION_DOWNGRADE' "$OUT/upgrade.log"; then
          fail "VERSION DOWNGRADE: this build's versionCode is older than $PREV. Not a signing problem."
        else
          fail "UPGRADE FAILED over $PREV -- see the adb output above for the reason."
        fi
      fi
    fi
  fi
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "PASS: the release build installs, launches, and survives."
else
  echo "FAIL: see errors above."
fi

rm -f "$SIGNING_KEYSTORE_PATH" 2>/dev/null || true
exit "$FAILED"

#!/usr/bin/env bash
# targetSdk still meets Google Play's submission requirement -- and this check knows
# when its own knowledge has expired.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="140-target-sdk-submittable"
    TITLE="targetSdk meets Play's submission requirement"
    CATCHES="An app that builds perfectly and cannot be submitted.

Google Play raises its minimum target API roughly annually. An app below it is not
rejected at build time by anything -- it compiles, signs, installs, runs, and is
simply refused at upload. The pipeline had no representation of that requirement at
all, so every app it generated was pinned at compileSdk 34 and would have been
unsubmittable from 31 August 2026, discovered at the worst possible moment.

Found 16 days before that date, with the whole lattice still on AGP 8.3 whose
ceiling is API 34 -- so the fix was not a one-line bump but AGP, Gradle, Kotlin, KSP,
Hilt and Room moving together.

THIS CHECK ALSO EXPIRES. A hardcoded requirements table is itself a config with a
canonical upstream, and a stale one under-enforces silently -- which is worse than
no check, because it looks like coverage. So it warns when its own data is older
than the cadence at which the requirement moves."
    SCOPE="android"
}
meta
ROOT="$(af_root "${1:-}")"

# ── THE REQUIREMENT TABLE ───────────────────────────────────────────────────
# Verified 2026-08-15 against:
#   https://developer.android.com/google/play/requirements/target-sdk
#
# Format: <YYYY-MM-DD the requirement takes effect> <minimum targetSdk from then>
# Add a row when Google announces the next one; do not delete old rows -- they
# document what was true when an older app was released.
TABLE_VERIFIED="2026-08-15"
REQUIREMENTS="
2024-08-31 34
2025-08-31 35
2026-08-31 36
"
# The requirement moves about once a year, so a table older than ~10 months is
# probably behind one. Warn rather than fail: being out of date is not the project's
# bug, but pretending to enforce something you last checked a year ago is.
STALE_AFTER_DAYS=300
# ────────────────────────────────────────────────────────────────────────────

APP_BUILD="$ROOT/app/build.gradle.kts"
[ -f "$APP_BUILD" ] || APP_BUILD="$ROOT/app/build.gradle"
[ -f "$APP_BUILD" ] || { pass "$TITLE (no app module)"; af_exit; }

# Read the literal, or resolve a {{TARGET_SDK}} placeholder from an unrendered
# template rather than reporting a confusing parse failure on it.
target=$(grep -oE 'targetSdk\s*=\s*[0-9]+' "$APP_BUILD" 2>/dev/null | grep -oE '[0-9]+' | head -1)
if [ -z "$target" ]; then
    if grep -q 'targetSdk\s*=\s*{{' "$APP_BUILD" 2>/dev/null; then
        pass "$TITLE (unrendered template)"
        af_exit
    fi
    pass "$TITLE (no targetSdk declared)"
    af_exit
fi

today=$(date -u +%Y-%m-%d)
required=0
required_from=""
next_date=""
next_sdk=""

while read -r eff sdk; do
    [ -z "$eff" ] && continue
    if [[ "$eff" < "$today" || "$eff" == "$today" ]]; then
        # Already in force.
        if [ "$sdk" -gt "$required" ]; then required="$sdk"; required_from="$eff"; fi
    elif [ -z "$next_date" ]; then
        next_date="$eff"; next_sdk="$sdk"
    fi
done <<< "$REQUIREMENTS"

failed=0
if [ "$required" -gt 0 ] && [ "$target" -lt "$required" ]; then
    fail "targetSdk $target cannot be submitted to Google Play: $required has been required since $required_from"
    note "the app builds and runs; it is refused at UPLOAD, which is why nothing else catches it"
    failed=1
fi

# A deadline you are told about a week before is a deadline you miss. Warn while the
# next one is still cheap to act on -- a target bump usually drags the whole
# AGP/Kotlin/KSP lattice with it, which is days of work, not minutes.
if [ -n "$next_date" ] && [ "$target" -lt "$next_sdk" ]; then
    days=$(( ( $(date -u -d "$next_date" +%s) - $(date -u -d "$today" +%s) ) / 86400 ))
    if [ "$days" -le 120 ]; then
        note "targetSdk $next_sdk required from $next_date -- $days days away, and currently $target"
        note "budget for the whole lattice: AGP, Gradle, Kotlin, KSP, Hilt and Room move together"
    fi
fi

age=$(( ( $(date -u -d "$today" +%s) - $(date -u -d "$TABLE_VERIFIED" +%s) ) / 86400 ))
if [ "$age" -gt "$STALE_AFTER_DAYS" ]; then
    note "this check's requirement table was last verified $TABLE_VERIFIED, $age days ago"
    note "the requirement moves about yearly, so it is probably behind one -- re-verify at"
    note "https://developer.android.com/google/play/requirements/target-sdk"
fi

[ "$failed" -eq 0 ] && pass "$TITLE (targetSdk $target, required $required)"
af_exit

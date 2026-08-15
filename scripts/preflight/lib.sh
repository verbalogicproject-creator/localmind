#!/usr/bin/env bash
# Shared helpers for preflight checks.
#
# THE CHECK CONTRACT
#
#   1. A check takes the project root as "$1". This is the single change that makes
#      fixtures possible at all. The original preflight hardcoded
#      `cd "$(dirname "$0")/.."`, so it could only ever examine its own repo --
#      which is why three checks shipped without ever being run against the bug
#      they target, and all three passed while that bug was present.
#
#   2. A check defines meta() setting ID, TITLE, CATCHES and SCOPE. CATCHES names
#      the real incident, not an abstraction. A check with no incident behind it is
#      a guess and should be labelled as one.
#
#   3. A check exits 0 (clean) or 1 (problems found), and reports through pass/fail
#      so both a human and a machine can read the result.
#
# Multi-line matching uses `perl -0777` to slurp. A line-oriented grep against a
# multi-line `proguardFiles(...)` block silently matched nothing and reported
# success -- the first of the three near-miss patterns.

: "${AF_JSON:=0}"
AF_FAILURES=0
AF_FINDINGS=()

if [ -t 1 ] && [ "$AF_JSON" != "1" ]; then
    AF_GREEN=$'\033[32m'; AF_RED=$'\033[31m'; AF_DIM=$'\033[2m'; AF_OFF=$'\033[0m'
else
    AF_GREEN=""; AF_RED=""; AF_DIM=""; AF_OFF=""
fi

pass() {
    [ "$AF_JSON" = "1" ] || printf '%sok%s   %s\n' "$AF_GREEN" "$AF_OFF" "$1"
}

fail() {
    AF_FAILURES=$((AF_FAILURES + 1))
    AF_FINDINGS+=("$1")
    if [ "$AF_JSON" = "1" ]; then
        printf '{"check":"%s","level":"error","message":"%s"}\n' \
            "${ID:-unknown}" "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"
    else
        printf '%sFAIL%s %s\n' "$AF_RED" "$AF_OFF" "$1"
    fi
}

note() {
    [ "$AF_JSON" = "1" ] || printf '%s     %s%s\n' "$AF_DIM" "$1" "$AF_OFF"
}

# Slurp a whole file. Use for anything whose syntax spans lines.
slurp() { perl -0777 -ne 'print' "$1" 2>/dev/null; }

# Resolve the project root argument, with a clear error rather than a silent
# examination of the wrong directory.
af_root() {
    local root="${1:-}"
    if [ -z "$root" ]; then
        printf 'usage: %s <project-root>\n' "$(basename "${0}")" >&2
        exit 2
    fi
    if [ ! -d "$root" ]; then
        printf 'not a directory: %s\n' "$root" >&2
        exit 2
    fi
    (cd "$root" && pwd)
}

# Where Kotlin/Java sources live, across every source set.
# Scans test/ and androidTest/ too: `./gradlew build` compiles unit tests, so a
# stale import there fails the build exactly like a stale import in main.
af_src_dirs() {
    local root="$1" d dirs=()
    for d in "$root"/app/src/*/java "$root"/app/src/*/kotlin; do
        [ -d "$d" ] && dirs+=("$d")
    done
    printf '%s\n' "${dirs[@]}"
}

af_exit() { [ "$AF_FAILURES" -eq 0 ] && exit 0 || exit 1; }

#!/usr/bin/env bash
#
# preflight -- the cheap rung.
#
#   bash scripts/preflight.sh [project-root] [--json] [--selftest]
#
# Every check here exists because of a failure that actually shipped. None is
# hypothetical, and each ships with a fixture reproducing the bug it targets --
# because three checks in this lineage passed while their own bug was present, and
# a check that reports success without verifying anything is worse than no check:
# it ends the investigation.
#
# This runs in about two seconds and needs no JDK and no Android SDK. A CI round
# trip is two to five minutes. That ratio is the entire economic argument.
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKS_DIR="$HERE/preflight/checks"

ROOT=""
MODE="run"
for arg in "$@"; do
    case "$arg" in
        --json)     export AF_JSON=1 ;;
        --selftest) MODE="selftest" ;;
        --*)        printf 'unknown option: %s\n' "$arg" >&2; exit 2 ;;
        *)          ROOT="$arg" ;;
    esac
done
ROOT="${ROOT:-$(pwd)}"

if [ "$MODE" = "selftest" ]; then
    exec bash "$HERE/preflight/selftest.sh"
fi

if [ ! -d "$CHECKS_DIR" ]; then
    printf 'no checks directory at %s\n' "$CHECKS_DIR" >&2
    exit 2
fi

failures=0
ran=0
skipped=0

# ── Suppressions ───────────────────────────────────────────────────────────
# .appfactory/preflight-ignore, one entry per line:
#
#   <check-id> | <reason>
#
# THE REASON IS MANDATORY. An entry without one is itself an error, because
# silent suppression is how check corpora die: someone disables a noisy check on
# a Friday, nobody remembers why, and a year later the corpus is decoration.
#
# Every suppression is printed on every run. A check you have turned off should
# be visible every time, not discovered later by someone reading a dotfile.
#
# The motivating case is genuine and self-resolving: check 090 requires a
# committed Room schema, but the schema can only be produced BY a build, so the
# first commit that introduces Room deadlocks -- preflight blocks the build that
# would generate the file that satisfies preflight.
IGNORE_FILE="$ROOT/.appfactory/preflight-ignore"
declare -A IGNORED=()
if [ -f "$IGNORE_FILE" ]; then
    while IFS= read -r line; do
        case "$line" in ''|'#'*) continue ;; esac
        id="$(printf '%s' "$line" | cut -d'|' -f1 | tr -d ' ')"
        reason="$(printf '%s' "$line" | cut -d'|' -f2- | sed 's/^ *//; s/ *$//')"
        if [ -z "$reason" ]; then
            printf '\033[31mFAIL\033[0m suppression of %s has no reason. Silent suppression is how check corpora die.\n' "$id"
            failures=$((failures + 1))
            continue
        fi
        IGNORED["$id"]="$reason"
    done < "$IGNORE_FILE"
fi

# checks/ then checks/local/ -- project-specific checks live in local/ and are
# never touched by `upgrade`, so a re-vendor cannot silently delete them.
for check in "$CHECKS_DIR"/*.sh "$CHECKS_DIR"/local/*.sh; do
    [ -e "$check" ] || continue

    # A check with no fixture directory does not run. This is the ratchet: it makes
    # "I'll add the fixture later" impossible rather than merely discouraged.
    id="$(basename "$check" .sh)"
    if [ ! -d "$HERE/preflight/fixtures/$id" ]; then
        printf '\033[31mFAIL\033[0m check %s ships without fixtures/%s/ -- refusing to run it\n' "$id" "$id"
        failures=$((failures + 1))
        continue
    fi

    if [ -n "${IGNORED[$id]:-}" ]; then
        printf '\033[33mskip\033[0m %s -- %s\n' "$id" "${IGNORED[$id]}"
        skipped=$((skipped + 1))
        continue
    fi

    bash "$check" "$ROOT" || failures=$((failures + 1))
    ran=$((ran + 1))
done

echo
if [ "$failures" -eq 0 ]; then
    printf '\033[32mPreflight clean\033[0m (%d checks). Push and watch:\n' "$ran"
    printf '  gh run list --commit $(git rev-parse HEAD)   # FULL sha; a short one returns nothing, silently\n'
else
    printf '\033[31mPreflight found %d problem(s)\033[0m across %d checks.\n' "$failures" "$ran"
    printf 'Fix before pushing -- each CI round trip is 2-5 minutes.\n'
fi
exit $(( failures > 0 ? 1 : 0 ))

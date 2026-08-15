#!/usr/bin/env bash
#
# selftest -- verify that every check actually detects the bug it claims to catch.
#
# THE PROBLEM THIS SOLVES
#
# Three checks in this corpus's history passed while the bug they targeted was
# present, and all three failed the same way: a pattern that was VERY NEARLY RIGHT.
#
#   - a line-oriented grep against a multi-line proguardFiles(...) block
#   - an alternation matching any artifact containing "compose"
#   - a case-sensitive prefix that missed testProguardFiles(...)
#
# Nearly right is the dangerous state. A check that reports success without
# verifying anything is worse than no check: it ends the investigation.
#
# So every check runs against fixtures:
#
#   fixtures/<id>/bug/      the check MUST exit 1 here
#   fixtures/<id>/fixed/    the check MUST exit 0 here
#   fixtures/<id>/bug-*/    additional evasion variants; MUST exit 1
#
# Run in CI (no JDK needed, about two seconds). A pull request adding a check
# without fixtures is red before it can be merged.
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKS="$HERE/checks"
FIXTURES="$HERE/fixtures"

G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; O=$'\033[0m'
fails=0
tested=0

run_check() {  # check_path fixture_dir -> exit code, output suppressed
    bash "$1" "$2" >/dev/null 2>&1
}

for check in "$CHECKS"/*.sh "$CHECKS"/local/*.sh; do
    [ -e "$check" ] || continue
    id="$(basename "$check" .sh)"
    fx="$FIXTURES/$id"

    if [ ! -d "$fx/bug" ]; then
        printf '%sFAIL%s %s ships without fixtures/%s/bug/ -- it has never been observed detecting anything\n' \
            "$R" "$O" "$id" "$id"
        fails=$((fails + 1)); continue
    fi
    if [ ! -d "$fx/fixed" ]; then
        printf '%sFAIL%s %s ships without fixtures/%s/fixed/ -- it has never been observed passing cleanly\n' \
            "$R" "$O" "$id" "$id"
        fails=$((fails + 1)); continue
    fi

    tested=$((tested + 1))
    ok=1

    if run_check "$check" "$fx/bug"; then
        printf '%sFAIL%s %s does NOT detect its own bug (exited 0 on fixtures/%s/bug/)\n' "$R" "$O" "$id" "$id"
        ok=0
    fi
    if ! run_check "$check" "$fx/fixed"; then
        printf '%sFAIL%s %s false-positives on correct code (exited 1 on fixtures/%s/fixed/)\n' "$R" "$O" "$id" "$id"
        ok=0
    fi

    # Evasion variants: bugs that a nearly-right pattern would let through.
    for variant in "$fx"/bug-*/; do
        [ -d "$variant" ] || continue
        vname="$(basename "$variant")"
        if run_check "$check" "$variant"; then
            printf '%sFAIL%s %s missed evasion variant %s\n' "$R" "$O" "$id" "$vname"
            ok=0
        fi
    done

    if [ "$ok" -eq 1 ]; then
        nvar=$(find "$fx" -maxdepth 1 -type d -name 'bug-*' 2>/dev/null | wc -l)
        extra=""
        [ "$nvar" -gt 0 ] && extra=" +${nvar} evasion variant(s)"
        printf '%sok%s   %s detects its bug, passes clean code%s\n' "$G" "$O" "$id" "$extra"
    else
        fails=$((fails + 1))
    fi
done

# A check with no fixture never reaches the loop above as "tested", but an orphan
# fixture directory means a check was deleted and its evidence left behind.
for fx in "$FIXTURES"/*/; do
    [ -d "$fx" ] || continue
    id="$(basename "$fx")"
    if [ ! -f "$CHECKS/$id.sh" ] && [ ! -f "$CHECKS/local/$id.sh" ]; then
        printf '%swarn%s fixtures/%s/ has no corresponding check\n' "$Y" "$O" "$id"
    fi
done

echo
if [ "$fails" -eq 0 ]; then
    printf '%sSelftest clean%s -- %d checks each verified against the bug they target.\n' "$G" "$O" "$tested"
    exit 0
fi
printf '%sSelftest FAILED%s -- %d check(s) are unvalidated.\n' "$R" "$O" "$fails"
printf 'An unvalidated check is worse than no check: it reports success and ends the investigation.\n'
exit 1

#!/usr/bin/env bash
# No scaffold placeholder survived into the generated project.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="110-no-unsubstituted-placeholders"
    TITLE="no unsubstituted scaffold placeholders"
    CATCHES="A {{PLACEHOLDER}} that the scaffold failed to render. The generated file
is syntactically valid and looks plausible, so nothing upstream complains -- it fails
only when something USES the value.

The incident: scaffold.py rendered templates/ and workflows/ but copied scripts/
verbatim, so emulator-verify.sh kept a literal com.verbalogix.assistant. The emulator
reported

  Error: Activity class {com.verbalogix.assistant/com.verbalogix.assistant.MainActivity} does not exist

after a ten-minute run. Caught honestly by the launch smoke, at the second-most
expensive rung, for something a two-second grep can see.

Deliberately ignores GitHub Actions \${{ ... }}, which is a different syntax that
happens to share braces, and the fixture trees, which contain intentional templates."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

# Scan GENERATED OUTPUT only. Three things legitimately contain placeholders and
# must be excluded, or the check drowns its own finding in noise:
#
#   scripts/preflight/   the check corpus, including this file, which quotes
#                        placeholders in its own documentation
#   .appfactory/bin/     the renderer -- scaffold.py names every placeholder it
#                        substitutes, by definition
#   fixtures/            deliberately-broken sample projects
#
# Same lesson as check 010: a corpus that scans itself reports its own contents as
# defects. Found the same way, by running it against a real repo.
found=0
while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    fail "unsubstituted placeholder -- $hit"
    found=1
done < <(
    grep -rnoE '\{\{[A-Z][A-Z0-9_]*\}\}' "$ROOT" 2>/dev/null \
        | grep -Fv "$ROOT/scripts/preflight/" \
        | grep -Fv "$ROOT/.appfactory/bin/" \
        | grep -v '/\.git/' \
        | grep -v '/build/' \
        | while IFS= read -r line; do
            file="${line%%:*}"; rest="${line#*:}"; lineno="${rest%%:*}"; ph="${rest#*:}"
            # GitHub Actions expressions are ${{ ... }} -- the $ makes them different
            # syntax, and their contents are lowercase/dotted anyway.
            if sed -n "${lineno}p" "$file" 2>/dev/null | grep -qF "\${$ph"; then continue; fi
            printf '%s:%s %s\n' "${file#$ROOT/}" "$lineno" "$ph"
          done
)

[ "$found" -eq 0 ] && pass "$TITLE"
af_exit

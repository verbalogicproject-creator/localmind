#!/usr/bin/env bash
# Every libs.* alias used in a build file exists in the version catalog.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="010-catalog-aliases"
    TITLE="version catalog aliases resolve"
    CATCHES="A build file referencing libs.foo.bar with no such entry in
gradle/libs.versions.toml. Gradle fails at CONFIGURATION time with an unresolved
reference, so nothing compiles and the error names the accessor rather than the
missing catalog entry."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

CATALOG="$ROOT/gradle/libs.versions.toml"
[ -f "$CATALOG" ] || { pass "$TITLE (no version catalog)"; af_exit; }

# Catalog keys use dots or dashes interchangeably in the accessor; normalise both
# sides to dashes before comparing.
declare -A DECLARED=()
while IFS= read -r key; do
    [ -n "$key" ] && DECLARED["${key//./-}"]=1
done < <(perl -ne '
    $sect = $1 if /^\s*\[([a-z]+)\]/;
    next unless $sect && $sect =~ /^(libraries|plugins|versions|bundles)$/;
    print "$1\n" if /^\s*([A-Za-z0-9_.-]+)\s*=/;
' "$CATALOG")

# Exclude build/ (generated) AND the vendored check corpus's own fixtures.
#
# Without the second exclusion this finds fixtures/010-catalog-aliases/bug/, which
# contains a deliberately-missing alias, and every freshly scaffolded app opens with
# a false failure. Found by running preflight against a generated app rather than by
# reading the code: the corpus must not scan itself.
#
# The exclusion is anchored to "$ROOT/scripts/preflight/fixtures", NOT to any path
# matching /preflight/fixtures/. The first attempt used the substring and broke the
# selftest, which points the check AT a fixture directory — the exclusion then
# swallowed the very file under test and the check reported clean. The selftest
# caught that within seconds, which is the entire reason it exists.
EXCLUDE_DIR="$ROOT/scripts/preflight/fixtures"
BUILD_FILES=()
while IFS= read -r f; do BUILD_FILES+=("$f"); done < <(
    find "$ROOT" \( -name "build.gradle.kts" -o -name "build.gradle" \) 2>/dev/null \
        | grep -v '/build/' \
        | grep -Fv "$EXCLUDE_DIR/"
)
[ ${#BUILD_FILES[@]} -gt 0 ] || { pass "$TITLE (no build files)"; af_exit; }

missing=0
while IFS= read -r use; do
    [ -z "$use" ] && continue
    norm="${use//./-}"
    if [ -z "${DECLARED[$norm]:-}" ]; then
        fail "build file uses 'libs.$use' but no such alias exists in gradle/libs.versions.toml"
        missing=1
    fi
done < <(
    grep -rhoE 'libs\.(plugins\.)?[A-Za-z0-9.]+' "${BUILD_FILES[@]}" 2>/dev/null \
        | sed 's/^libs\.//; s/^plugins\.//' \
        | sed 's/[.]$//' \
        | sort -u
)

[ "$missing" -eq 0 ] && pass "$TITLE"
af_exit

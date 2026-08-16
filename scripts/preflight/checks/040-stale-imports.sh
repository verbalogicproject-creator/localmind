#!/usr/bin/env bash
# Imports of the project's own package point at symbols that still exist.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="040-stale-imports"
    TITLE="internal imports resolve to a declaration"
    CATCHES="An import left behind when a class is deleted or renamed. Compiles
nowhere and fails the whole build; the incident here was a stale GeminiApiClient
import surviving a refactor.

Scans test/ and androidTest/ as well as main/, because './gradlew build' compiles
unit tests and a stale import there fails the build exactly like one in main. That
distinction cost a CI round trip: an integration test referencing a deleted symbol
blocked every build while nobody was looking at test sources."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

mapfile -t SRC < <(af_src_dirs "$ROOT")
[ ${#SRC[@]} -gt 0 ] || { pass "$TITLE (no sources)"; af_exit; }

# The app's own package prefix, from the namespace or applicationId.
APP_BUILD="$ROOT/app/build.gradle.kts"
[ -f "$APP_BUILD" ] || APP_BUILD="$ROOT/app/build.gradle"
PKG=$(grep -oE 'namespace\s*=\s*"[^"]+"' "$APP_BUILD" 2>/dev/null | head -1 | sed 's/.*"\(.*\)"/\1/')
[ -n "$PKG" ] || { pass "$TITLE (no namespace declared)"; af_exit; }

# Classes AGP GENERATES at build time. They are imported from the app's own package
# and have no source declaration anywhere, so a source-only check cannot see them and
# would report every correct use as a stale import.
#
# Found by this check firing on `import <pkg>.BuildConfig` in a file that compiles
# perfectly. A check that cries wolf gets ignored, and an ignored check is worse than
# no check because it still looks like coverage.
#
# Deliberately a short, named list rather than a pattern: a broad rule here would be
# the same mistake check 050 had to be rewritten for.
GENERATED="BuildConfig R Manifest"

missing=0
while IFS= read -r sym; do
    [ -z "$sym" ] && continue
    cls="${sym##*.}"
    case " $GENERATED " in *" $cls "*) continue ;; esac
    # A declaration of that simple name anywhere in the project's sources.
    #
    # THREE FORMS, because Kotlin has three and the first version of this check knew
    # only one. It matched `class|interface|object|enum class|typealias|fun <name>`,
    # which misses two things that are ordinary Kotlin and perfectly importable:
    #
    #   top-level properties      const val TAG_EVIDENCE_CLOSE = "..."
    #   extension functions       fun Modifier.minimumTouchTarget()
    #
    # The extension case is the subtler one: the declaration reads `fun Modifier.foo`,
    # so a pattern anchored as `fun +foo` never matches however correct the code is.
    #
    # Both were found by this check firing on a file that compiles -- the same way the
    # BuildConfig false positive above was found. A check that cries wolf gets ignored,
    # and an ignored check is worse than no check because it still looks like coverage.
    if ! grep -rqE "^[[:space:]]*(public |internal |private |abstract |open |sealed |data |const |expect |actual |external |inline |suspend )*((class|interface|object|enum class|typealias|val|var)[[:space:]]+$cls\b|fun[[:space:]]+([A-Za-z0-9_.<>?]+\.)?$cls\b)" \
           "${SRC[@]}" 2>/dev/null; then
        fail "import $sym refers to a symbol with no declaration in this project"
        missing=1
    fi
done < <(
    grep -rhoE "^import +${PKG//./\\.}\.[A-Za-z0-9_.]+" "${SRC[@]}" 2>/dev/null \
        | sed 's/^import  *//' \
        | grep -vE '\.\*$' \
        | sort -u
)

[ "$missing" -eq 0 ] && pass "$TITLE"
af_exit

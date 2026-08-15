#!/usr/bin/env bash
# Known androidx sub-packages that need their own artifact.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="050-androidx-artifacts"
    TITLE="androidx sub-packages have their required artifact"
    CATCHES="Importing an androidx sub-package whose artifact was never declared. The
incident: androidx.lifecycle.compose.collectAsStateWithLifecycle with only
lifecycle-runtime-ktx on the classpath. ONE root error produced FOUR cascade errors,
including a type mismatch against Int that appears nowhere in the source -- the
failed import made the symbol an error type and everything touching it reported
nonsense.

DELIBERATELY A CURATED LIST, NOT A RULE.

The first version inferred 'androidx.<group>.<sub> needs a <group>-*<sub> artifact'
and produced six false positives on a correct app: androidx.compose.material3 maps
to an artifact called 'material3', not 'compose-material3', and Compose resolves
through the BOM regardless. A check that cries wolf is how a corpus loses trust --
people stop reading it, and then it fails to be believed on the day it is right.

So this lists sub-packages that HAVE actually bitten. Add a row when a new one does,
with a fixture. Narrow and correct beats general and noisy."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

mapfile -t SRC < <(af_src_dirs "$ROOT")
[ ${#SRC[@]} -gt 0 ] || { pass "$TITLE (no sources)"; af_exit; }

CATALOG="$ROOT/gradle/libs.versions.toml"
APP_BUILD="$ROOT/app/build.gradle.kts"
[ -f "$APP_BUILD" ] || APP_BUILD="$ROOT/app/build.gradle"
DECLARED="$( { [ -f "$CATALOG" ] && cat "$CATALOG"; [ -f "$APP_BUILD" ] && cat "$APP_BUILD"; } 2>/dev/null )"
[ -n "$DECLARED" ] || { pass "$TITLE (nothing declared)"; af_exit; }

# import prefix | artifact pattern that must be declared | what breaks without it
TRAPS=(
  "androidx.lifecycle.compose|lifecycle-[a-z0-9-]*compose|collectAsStateWithLifecycle is unresolved, and every symbol touching its result reports nonsense"
  "androidx.navigation.compose|navigation-[a-z0-9-]*compose|NavHost and composable() are unresolved"
  "androidx.hilt.navigation.compose|hilt-navigation-compose|hiltViewModel() is unresolved"
  "androidx.paging.compose|paging-[a-z0-9-]*compose|collectAsLazyPagingItems is unresolved"
  "androidx.room.testing|room-testing|MigrationTestHelper is unresolved, so the migration cannot be tested"
)

missing=0
for trap in "${TRAPS[@]}"; do
    IFS='|' read -r prefix artifact consequence <<< "$trap"
    esc="${prefix//./\\.}"
    if grep -rqE "^import +${esc}\b" "${SRC[@]}" 2>/dev/null; then
        if ! printf '%s' "$DECLARED" | grep -qE "$artifact"; then
            fail "sources import $prefix.* but no '$artifact' artifact is declared -- $consequence"
            missing=1
        fi
    fi
done

[ "$missing" -eq 0 ] && pass "$TITLE"
af_exit

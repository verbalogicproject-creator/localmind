#!/usr/bin/env bash
# Every @resource the manifest references actually exists.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="030-manifest-resources"
    TITLE="all manifest resource references resolve"
    CATCHES="AAPT 'resource not found' failures, which abort the build during
resource linking rather than at compile time, so the error names the manifest line
instead of the missing resource's owner.

Two real incidents:
  - @mipmap/ic_launcher referenced after an icon purge
  - @style/Theme.Foo referenced while themes.xml still declared Theme.Bar, because a
    scaffold templated the manifest but not the theme file. That one shipped in the
    FIRST app this pipeline generated, and slipped through because this check had
    been dropped during extraction."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
[ -f "$MANIFEST" ] || { pass "$TITLE (no manifest)"; af_exit; }
RES="$ROOT/app/src/main/res"
[ -d "$RES" ] || { pass "$TITLE (no res/)"; af_exit; }

missing=0
# Matches @mipmap/foo, @style/Foo, @drawable/bar, @string/baz, @xml/qux ...
while IFS= read -r ref; do
    [ -z "$ref" ] && continue
    type="${ref%%/*}"
    name="${ref#*/}"
    found=0

    case "$type" in
        string|style|color|bool|integer|dimen|array)
            # Value resources: declared by name= inside res/values*/*.xml
            if grep -rqE "name=\"$name\"" "$RES"/values*/ 2>/dev/null; then found=1; fi
            ;;
        *)
            # File resources: a file of that name in any density variant of the dir
            if find "$RES" -type d -name "$type*" 2>/dev/null \
                 | xargs -r -I{} find {} -maxdepth 1 -name "$name.*" 2>/dev/null | grep -q .; then
                found=1
            fi
            # Adaptive icons may also be declared as values (rare but legal)
            grep -rqE "name=\"$name\"" "$RES"/values*/ 2>/dev/null && found=1
            ;;
    esac

    if [ "$found" -eq 0 ]; then
        fail "AndroidManifest.xml references @$type/$name but no such resource exists"
        missing=1
    fi
done < <(
    grep -oE '@(mipmap|drawable|style|string|color|xml|layout|array|bool|integer|dimen)/[A-Za-z0-9_.]+' \
        "$MANIFEST" 2>/dev/null | sed 's/^@//' | sort -u
)

[ "$missing" -eq 0 ] && pass "$TITLE"
af_exit

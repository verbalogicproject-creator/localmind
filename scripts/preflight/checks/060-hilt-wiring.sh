#!/usr/bin/env bash
# Hilt wiring is complete: @HiltAndroidApp exists AND the manifest instantiates it.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="060-hilt-wiring"
    TITLE="Hilt wiring complete"
    CATCHES="@AndroidEntryPoint or @HiltViewModel with no @HiltAndroidApp Application,
or the annotation sitting on a class the manifest never instantiates. Both throw at
launch with 'Hilt Activity must be attached to an @HiltAndroidApp Application'.

Both compile perfectly. NINE consecutive green builds shipped this exact bug, and no
compiler, linter or test could see it.

Annotations are matched at LINE START. An explanatory comment containing the word
@HiltAndroidApp once satisfied a naive presence-grep, so the check reported success
while the annotation was genuinely absent -- the second of this corpus's near-miss
failures. The bug-comment-only fixture exists to keep that from recurring."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

mapfile -t SRC < <(af_src_dirs "$ROOT")
[ ${#SRC[@]} -gt 0 ] || { pass "$TITLE (no sources)"; af_exit; }

# Only meaningful if something actually consumes Hilt.
if ! grep -rqE '^\s*@(AndroidEntryPoint|HiltViewModel)\b' "${SRC[@]}" 2>/dev/null; then
    pass "$TITLE (Hilt not in use)"
    af_exit
fi

ok=1
if ! grep -rqE '^\s*@HiltAndroidApp\b' "${SRC[@]}" 2>/dev/null; then
    fail "@AndroidEntryPoint/@HiltViewModel is used but no @HiltAndroidApp Application exists -- this crashes at launch"
    ok=0
else
    MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
    if [ ! -f "$MANIFEST" ]; then
        fail "no AndroidManifest.xml at app/src/main/"
        ok=0
    else
        # <application> and its android:name routinely sit on different lines, so
        # the manifest is slurped rather than grepped line by line.
        app_attr=$(perl -0777 -ne '
            if (/<application\b(.*?)>/s) { my $a=$1; print $1 if $a =~ /android:name="([^"]+)"/ }
        ' "$MANIFEST" 2>/dev/null)

        if [ -z "$app_attr" ]; then
            fail "@HiltAndroidApp exists but <application> has no android:name, so the class is never instantiated"
            ok=0
        else
            cls="${app_attr##*.}"
            if ! grep -rlE '^\s*@HiltAndroidApp\b' "${SRC[@]}" 2>/dev/null \
                 | xargs -r grep -lE "class +$cls\b" >/dev/null 2>&1; then
                fail "the manifest instantiates '$app_attr' but @HiltAndroidApp is on a different class"
                ok=0
            fi
        fi
    fi
fi

[ "$ok" -eq 1 ] && pass "$TITLE (@HiltAndroidApp present and registered in the manifest)"
af_exit

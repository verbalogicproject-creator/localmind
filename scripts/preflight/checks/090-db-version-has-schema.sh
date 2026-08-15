#!/usr/bin/env bash
# Every declared @Database version has a committed schema JSON.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="090-db-version-has-schema"
    TITLE="every @Database version has a committed schema"
    CATCHES="Bumping the Room schema version without committing the exported JSON.
Fails at RUNTIME on the emulator with

  FileNotFoundException: Cannot find the schema file in the assets folder

which reads like a broken test and is a missing file.

Committing matters because a clean CI checkout has no app/schemas/, and the
androidTest asset merge does not wait for KSP to write it. Putting the directory on
the asset path is necessary and NOT sufficient.

This is the first check in this corpus demoted from the emulator rung: it cost a
12-minute emulator round trip to discover and now costs two seconds."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

mapfile -t SRC < <(af_src_dirs "$ROOT")
[ ${#SRC[@]} -gt 0 ] || { pass "$TITLE (no sources)"; af_exit; }

mapfile -t DBFILES < <(grep -rlE '@Database\b' "${SRC[@]}" 2>/dev/null)
[ ${#DBFILES[@]} -gt 0 ] || { pass "$TITLE (no Room database)"; af_exit; }

# @Database(...) routinely spans lines, so slurp rather than grep.
versions=$(perl -0777 -ne '
    while (/\@Database\s*\((.*?)\)/gs) {
        my $b = $1;
        print "$1\n" if $b =~ /version\s*=\s*(\d+)/;
    }
' "${DBFILES[@]}" 2>/dev/null | sort -u)

[ -n "$versions" ] || { pass "$TITLE (no version declared)"; af_exit; }

# exportSchema=false is a deliberate choice to have no schemas; respect it.
if grep -rqE 'exportSchema\s*=\s*false' "${DBFILES[@]}" 2>/dev/null; then
    pass "$TITLE (exportSchema = false)"
    af_exit
fi

ok=1
for v in $versions; do
    if ! find "$ROOT/app/schemas" -name "$v.json" 2>/dev/null | grep -q .; then
        fail "@Database declares version $v but no committed app/schemas/**/$v.json exists"
        ok=0
    fi
done
[ "$ok" -eq 1 ] && pass "$TITLE ($(echo $versions | tr '\n' ' '))"
af_exit

#!/usr/bin/env bash
# Files named by the build actually exist on disk.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="020-build-referenced-files"
    TITLE="every build-referenced file is present"
    CATCHES="proguardFiles naming a proguard-rules.pro that was never created, and
the google-services plugin applied with no google-services.json. Both fail the
build; both cost a full CI round trip to discover.

This check has TWO near-miss failures in its own history, and both let the bug
through while reporting success:
  1. a line-oriented grep could not match a multi-line proguardFiles(...) block
  2. a case-sensitive prefix matched only 'proguardFiles', so
     testProguardFiles(\"proguard-test-rules.pro\") sailed past with the file deleted
Hence the slurp, and hence \\w*[Pp]roguardFiles? rather than a literal."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

APP_BUILD="$ROOT/app/build.gradle.kts"
[ -f "$APP_BUILD" ] || APP_BUILD="$ROOT/app/build.gradle"
[ -f "$APP_BUILD" ] || { pass "$TITLE (no app build file)"; af_exit; }

missing=0
while IFS= read -r f; do
    [ -z "$f" ] && continue
    if [ ! -f "$ROOT/app/$f" ] && [ ! -f "$ROOT/$f" ]; then
        fail "the build references '$f' but no such file exists"
        missing=1
    fi
done < <(
    perl -0777 -ne '
        while (/\w*[Pp]roguardFiles?\s*\((.*?)\)\s*$/gms) {
            my $b = $1; push @f, $b =~ /"([^"]+)"/g;
        }
        print "$_\n" for @f;
    ' "$APP_BUILD" 2>/dev/null | grep -v '^proguard-android'
)

if grep -q 'google.services' "$APP_BUILD" 2>/dev/null; then
    if [ ! -f "$ROOT/app/google-services.json" ]; then
        fail "the google-services plugin is applied but app/google-services.json is absent"
        missing=1
    fi
fi

[ "$missing" -eq 0 ] && pass "$TITLE"
af_exit

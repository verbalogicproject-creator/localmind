#!/usr/bin/env bash
# Every XML resource is well-formed.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="070-xml-resources-parse"
    TITLE="all XML resources are well-formed"
    CATCHES="A lexical fault no structural check can see. The instance that motivated
this: a DOUBLE HYPHEN inside an XML comment, which the spec forbids. AAPT fails with
a bare 'ParseError at [row,col]' and no explanation, and the file looks entirely
correct -- it is trivially easy to introduce by carrying an em-dash habit over from
shell or Kotlin, where the same characters are harmless.

Parsed with a real XML parser, never a regex. The first attempt at this check used a
regex and false-positived on the '-->' terminator itself."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

command -v python3 >/dev/null 2>&1 || { note "python3 unavailable; skipping $ID"; af_exit; }
[ -d "$ROOT/app/src" ] || { pass "$TITLE (no sources)"; af_exit; }

bad=$(python3 - "$ROOT" <<'PY' 2>/dev/null
import glob, os, sys, xml.etree.ElementTree as ET
root = sys.argv[1]
for f in sorted(glob.glob(os.path.join(root, 'app/src/**/*.xml'), recursive=True)):
    try:
        ET.parse(f)
    except ET.ParseError as e:
        print(f"{os.path.relpath(f, root)}: {e}")
PY
)

if [ -n "$bad" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] && fail "malformed XML resource -- $line"
    done <<< "$bad"
else
    pass "$TITLE"
fi
af_exit

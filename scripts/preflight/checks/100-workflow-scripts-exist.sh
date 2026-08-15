#!/usr/bin/env bash
# Scripts invoked by workflows actually exist in the repo.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="100-workflow-scripts-exist"
    TITLE="workflow-invoked scripts exist"
    CATCHES="A workflow calling a script at a path that does not exist. The runner
fails with a bare interpreter error:

  python3: can't open file '.../scripts/verify_mapping.py': [Errno 2] No such file

which names the interpreter's complaint rather than the workflow line that is wrong.

The incident: a scaffold vendored its helper scripts to .appfactory/bin/ while the
workflow template still called them from scripts/. It survived being templated
because the PATH was wrong, not the substitution — nothing about the text looked
suspicious. Cost a full CI round trip after the build, tests and R8 had all passed.

Same class as check 020, which does this for the build files, and the same reason it
matters: the failure happens on a runner minutes away rather than locally in
milliseconds."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

WF="$ROOT/.github/workflows"
[ -d "$WF" ] || { pass "$TITLE (no workflows)"; af_exit; }

missing=0
while IFS= read -r line; do
    [ -z "$line" ] && continue
    wf="${line%%:*}"
    path="${line#*:}"
    # Skip anything with a shell variable or a glob: not statically resolvable.
    case "$path" in *'$'*|*'*'*|*'{'*) continue ;; esac
    if [ ! -f "$ROOT/$path" ]; then
        fail "$(basename "$wf") invokes '$path' but no such file exists in the repo"
        missing=1
    fi
done < <(
    for f in "$WF"/*.y*ml; do
        [ -e "$f" ] || continue
        # bash foo.sh / python3 foo.py / sh foo.sh, with a repo-relative path
        grep -ohE '(bash|sh|python3?|node) +[A-Za-z0-9_./-]+\.(sh|py|js)' "$f" 2>/dev/null \
            | awk -v w="$f" '{print w":"$2}'
    done | sort -u
)

[ "$missing" -eq 0 ] && pass "$TITLE"
af_exit

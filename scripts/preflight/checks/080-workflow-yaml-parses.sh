#!/usr/bin/env bash
# Every GitHub Actions workflow file is valid YAML.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="080-workflow-yaml-parses"
    TITLE="all workflow YAML files parse"
    CATCHES="An invalid workflow, which fails in an unusually quiet way. GitHub still
creates a run, attributes it to whatever push introduced the file, marks it failed,
and names it after the FILE PATH instead of the workflow's name: -- because it could
not read the name. 'gh run view --log-failed' then returns 'log not found', since no
job ever started. Nothing in that chain says 'your YAML is broken'.

The instance that motivated this: a heredoc written flush-left inside a 'run: |'
block scalar. Content less indented than the block terminates it, which is invalid
YAML but looks entirely natural -- column 0 is exactly where a heredoc belongs in a
shell script. Cost: a tag pushed against a release workflow that could not run."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

command -v python3 >/dev/null 2>&1 || { note "python3 unavailable; skipping $ID"; af_exit; }
[ -d "$ROOT/.github/workflows" ] || { pass "$TITLE (no workflows)"; af_exit; }

out=$(python3 - "$ROOT" <<'PY' 2>/dev/null
import glob, os, sys
try:
    import yaml
except ImportError:
    print("SKIP"); sys.exit(0)
root = sys.argv[1]
for f in sorted(glob.glob(os.path.join(root, '.github/workflows/*.y*ml'))):
    try:
        yaml.safe_load(open(f))
    except Exception as e:
        print(f"{os.path.relpath(f, root)}: {str(e).splitlines()[0]}")
PY
)

if [ "$out" = "SKIP" ]; then
    # Say nothing rather than claim a check that did not run.
    note "pyyaml unavailable; $ID did not run"
elif [ -n "$out" ]; then
    while IFS= read -r line; do
        [ -n "$line" ] && fail "invalid workflow YAML -- $line"
    done <<< "$out"
else
    pass "$TITLE"
fi
af_exit

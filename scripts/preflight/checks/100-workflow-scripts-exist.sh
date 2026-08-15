#!/usr/bin/env bash
# Everything a workflow invokes actually exists in the repo, and can be run.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="100-workflow-scripts-exist"
    TITLE="workflow-invoked scripts exist"
    CATCHES="A workflow calling something that is not in the repo. The runner fails
with an error naming the interpreter's complaint rather than the workflow line that
is wrong:

  python3: can't open file '.../scripts/verify_mapping.py': [Errno 2] No such file
  /home/runner/work/_temp/xxx.sh: line 1: ./gradlew: No such file or directory

Incident 1: a scaffold vendored its helper scripts to .appfactory/bin/ while the
workflow template still called them from scripts/. It survived being templated
because the PATH was wrong, not the substitution — nothing about the text looked
suspicious. Cost a full CI round trip after the build, tests and R8 had all passed.

Incident 2: the scaffold shipped gradle/wrapper/gradle-wrapper.properties but no
gradlew, gradlew.bat or gradle-wrapper.jar, while all three workflows it generated
invoked ./gradlew. Every generated app passed the whole preflight corpus and then
died on the FIRST line of its FIRST build. This check could not see it: it looked
only for 'bash foo.sh' and 'python3 foo.py' forms, and ./gradlew is neither.

Three distinct failures, one question — can the runner actually execute this? — so
all three are answered here:

  missing         the file is not in the repo
  not executable  present but mode 644, which is what a naive template copy
                  produces; the runner says 'Permission denied'
  wrapper jar     gradlew present but gradle/wrapper/gradle-wrapper.jar absent,
                  which fails with 'Could not find or load main class
                  org.gradle.wrapper.GradleWrapperMain' and names nothing useful

Same class as check 020, which does this for the build files, and the same reason it
matters: the failure happens on a runner minutes away rather than locally in
milliseconds."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

WF="$ROOT/.github/workflows"
[ -d "$WF" ] || { pass "$TITLE (no workflows)"; af_exit; }

# Pull ./-prefixed invocations out of `run:` blocks ONLY.
#
# Restricting to run: is not fussiness. `./` appears constantly in `with:` params
# (path: ./app/build/outputs/...) naming a build OUTPUT that must not exist in the
# repo. A draft of this that grepped whole files flagged every upload-artifact step
# in this project's own workflows.
extract_invocations() {
    awk '
        {
            line = $0
            sub(/\r$/, "", line)
            if (line ~ /^[ \t]*$/) next
            indent = match(line, /[^ ]/) - 1
        }
        in_run && indent <= run_indent { in_run = 0 }
        {
            cmd = ""
            if (match(line, /(^|[ -]+)run:[ ]*/)) {
                run_indent = indent
                rest = substr(line, RSTART + RLENGTH)
                if (rest ~ /^[|>]/) { in_run = 1; next }   # block scalar follows
                cmd = rest                                 # inline form
            } else if (in_run) {
                cmd = line
            }
            if (cmd == "") next

            sub(/#.*/, "", cmd)                            # shell comments

            # A ./ path at the start of a command or after a shell operator.
            while (match(cmd, /(^|[ \t;&|(`])\.\/[A-Za-z0-9_.\/-]+/)) {
                tok = substr(cmd, RSTART, RLENGTH)
                sub(/^[ \t;&|(`]/, "", tok)
                print FILENAME "\t" tok
                cmd = substr(cmd, RSTART + RLENGTH)
            }
        }
    ' "$1"
}

missing=0

# --- form 1: an interpreter and a repo-relative path ------------------------
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

# --- form 2: ./something, executed directly ---------------------------------
while IFS=$'\t' read -r wf tok; do
    [ -z "${tok:-}" ] && continue
    case "$tok" in *'$'*|*'*'*|*'{'*) continue ;; esac
    rel="${tok#./}"
    base="$(basename "$rel")"

    if [ ! -f "$ROOT/$rel" ]; then
        fail "$(basename "$wf") runs '$tok' but no such file exists in the repo"
        if [ "$base" = "gradlew" ]; then
            note "the Gradle wrapper is FOUR committed files, not one:"
            note "  gradlew  gradlew.bat  gradle/wrapper/gradle-wrapper.jar  gradle-wrapper.properties"
            note "take them from a project that builds; the jar cannot be hand-authored"
        fi
        missing=1
        continue
    fi

    if [ ! -x "$ROOT/$rel" ]; then
        fail "$(basename "$wf") runs '$tok' but it is not executable — the runner says Permission denied"
        note "git tracks the mode: chmod +x $rel && git update-index --chmod=+x $rel"
        missing=1
    fi

    # gradlew is a launcher for a jar that must be committed beside it. Without the
    # jar it dies with a class-not-found error naming neither the jar nor the wrapper.
    if [ "$base" = "gradlew" ]; then
        d="$(dirname "$rel")"; [ "$d" = "." ] && d=""
        jar="$ROOT/${d:+$d/}gradle/wrapper/gradle-wrapper.jar"
        if [ ! -f "$jar" ]; then
            fail "$tok is present but gradle/wrapper/gradle-wrapper.jar is not committed"
            note "fails as: Could not find or load main class org.gradle.wrapper.GradleWrapperMain"
            missing=1
        fi
    fi
done < <(
    for f in "$WF"/*.y*ml; do
        [ -e "$f" ] || continue
        extract_invocations "$f"
    done | sort -u
)

[ "$missing" -eq 0 ] && pass "$TITLE"
af_exit

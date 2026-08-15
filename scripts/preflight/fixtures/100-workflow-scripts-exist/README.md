# 100-workflow-scripts-exist

**Incident 1:** a scaffold vendored its helper scripts to `.appfactory/bin/` while the
workflow template still called them from `scripts/`. The runner failed with

    python3: can't open file '.../scripts/verify_mapping.py': [Errno 2] No such file

which names the interpreter's complaint rather than the workflow line that is wrong.

It survived templating because the PATH was wrong, not the substitution — nothing
about the text looked suspicious. Cost a full CI round trip, *after* build, tests and
R8 had all passed, which is the most expensive place to discover a missing file.

`bug/` has `preflight.sh` present and `verify_mapping.py` absent, so the check must
distinguish the two rather than pass or fail the whole workflow wholesale.

**Incident 2:** the scaffold shipped `gradle/wrapper/gradle-wrapper.properties` but not
`gradlew`, `gradlew.bat` or `gradle-wrapper.jar`, while all three workflows it
generated invoke `./gradlew`. The generated app passed all fourteen checks and then
died on the first line of its first build:

    /home/runner/work/_temp/xxx.sh: line 1: ./gradlew: No such file or directory

The check could not see it, and the reason is worth keeping: it looked for
`bash foo.sh` and `python3 foo.py` — an interpreter followed by a path — and
`./gradlew` has no interpreter. The pattern was not wrong about what it matched, it
was wrong about what a workflow can invoke.

Found by audit, not by a failure, which is the only reason it was found before a
generated app was handed to anyone.

## The three variants, and why each is separate

Presence is not the whole question. The runner has to be able to *execute* the thing,
and it can fail to for three unrelated reasons, each with its own unhelpful message:

| fixture | state | what the runner says |
|---|---|---|
| `bug-gradlew/` | not in the repo | `./gradlew: No such file or directory` |
| `bug-gradlew-mode/` | present, mode 644 | `./gradlew: Permission denied` |
| `bug-gradlew-jar/` | present, +x, no jar | `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` |

`bug-gradlew-mode/` is not hypothetical: rendering a template through
`open(dst, "w")` creates mode 644, and git records the mode, so a scaffold that
copies `gradlew` as text ships an unrunnable one. `scaffold.py` now chmods it and
copies it verbatim, which also preserves `gradlew.bat`'s CRLF line endings — Python's
text mode silently rewrites them to LF, and `cmd.exe` mis-parses `goto` labels in an
LF-only batch file.

## The fixture jars are real, and they have to be

`gradlew` and `gradlew.bat` in these trees are placeholders — this check only asks
whether they exist. `gradle-wrapper.jar` is **the genuine published one**, and the
first attempt at these fixtures got that wrong.

A stub named `gradle-wrapper.jar` was committed on the reasoning that the check never
reads its bytes. True, and irrelevant: `gradle/actions/setup-gradle` runs with
`validate-wrappers: true` by default, globs **the entire repository** for that
filename, and checks every hit against the published Gradle release checksums. Two
fixture stubs failed the build of the app they were vendored into:

    ✗ Found unknown Gradle Wrapper JAR files:
      15ae5e4d… scripts/preflight/fixtures/…/bug-gradlew-mode/gradle/wrapper/gradle-wrapper.jar
      15ae5e4d… scripts/preflight/fixtures/…/fixed/gradle/wrapper/gradle-wrapper.jar
    ✓ Found known Gradle Wrapper JAR files:
      cb0da675… gradle/wrapper/gradle-wrapper.jar

That validation is a supply-chain control: a swapped wrapper jar is arbitrary code
execution on every build that repo ever runs. So the filename is not ours to reuse for
test data — **any file called `gradle-wrapper.jar` anywhere in the tree is a claim
about provenance**, and the fix is a real jar, never `validate-wrappers: false`.

It matters more than one red build, because `scaffold.py` vendors these fixtures into
every app it generates: a stub here fails the first CI run of every generated app,
which is the exact defect this check was written for, reintroduced one directory down.

### Why there is no fixture for a fake wrapper jar

There cannot be one. A `bug-*` tree containing a bogus `gradle-wrapper.jar` would trip
wrapper validation on this repo's own builds — the counterexample cannot coexist with
the check that detects it. So this rule is not enforced locally: authenticity is left
to `gradle/actions`, which owns the checksum list. That is the S6 rule holding, not an
exception to it — a config with a canonical upstream is fetched, never reproduced —
and the corpus rule holds too: no fixture, no check.

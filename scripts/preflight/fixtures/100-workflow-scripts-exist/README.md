# 100-workflow-scripts-exist

**Incident:** a scaffold vendored its helper scripts to `.appfactory/bin/` while the
workflow template still called them from `scripts/`. The runner failed with

    python3: can't open file '.../scripts/verify_mapping.py': [Errno 2] No such file

which names the interpreter's complaint rather than the workflow line that is wrong.

It survived templating because the PATH was wrong, not the substitution — nothing
about the text looked suspicious. Cost a full CI round trip, *after* build, tests and
R8 had all passed, which is the most expensive place to discover a missing file.

`bug/` has `preflight.sh` present and `verify_mapping.py` absent, so the check must
distinguish the two rather than pass or fail the whole workflow wholesale.

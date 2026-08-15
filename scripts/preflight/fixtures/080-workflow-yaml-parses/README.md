# 080-workflow-yaml-parses

**Incident:** a heredoc written flush-left inside a `run: |` block scalar. Content
less indented than the block terminates it, producing invalid YAML that looks
entirely natural — column 0 is exactly where a heredoc belongs in a shell script.

The failure is unusually quiet. GitHub still creates a run, attributes it to the
push, marks it failed, and names it after the FILE PATH instead of the workflow's
`name:` — because it could not read the name. `gh run view --log-failed` then returns
"log not found", since no job ever started.

**Cost: a tag pushed against a release workflow that could not run at all.**

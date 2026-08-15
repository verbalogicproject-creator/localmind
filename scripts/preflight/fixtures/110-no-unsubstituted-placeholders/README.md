# 110-no-unsubstituted-placeholders

**Incident:** `scaffold.py` rendered `templates/` and `workflows/` but copied
`scripts/` verbatim, so `emulator-verify.sh` kept a literal `{{APPLICATION_ID}}`.
The emulator reported

    Error: Activity class {{{APPLICATION_ID}}/{{APPLICATION_ID}}.MainActivity} does not exist

after a ten-minute run — caught honestly by the launch smoke, at the second-most
expensive rung in the ladder, for something a two-second grep can see.

The file was syntactically valid and looked plausible, which is why nothing upstream
complained. Only something USING the value could notice.

Both fixtures include a workflow with GitHub's `${{ }}` expressions, which share
braces but are different syntax and must not be flagged. A check that fires on every
Actions workflow would be worse than none.

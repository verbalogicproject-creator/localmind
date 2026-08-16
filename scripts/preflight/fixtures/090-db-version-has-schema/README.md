# 090-db-version-has-schema

**Incident:** the Room schema version was bumped to 2 without committing the exported
`2.json`. The emulator failed at RUNTIME with

    FileNotFoundException: Cannot find the schema file in the assets folder

which reads like a broken test and is a missing file.

Committing matters because a clean CI checkout has no `app/schemas/`, and the
androidTest asset merge does not wait for KSP to write it. Putting the directory on
the asset path is necessary and **not sufficient**.

**This is the first check demoted from the emulator rung** — it cost a 12-minute
round trip to discover and now costs two seconds. That direction of travel is the
economic argument for the whole corpus.

Note `@Database(...)` spans lines here on purpose: the version must be found by a
slurp, not a line-oriented grep.

## The historical schemas

`bug-migration-source-schema-deleted/` and `bug-automigration-schema-deleted/` cover a
quieter failure than a bumped version with no schema.

A `Migration(1, 2)` needs `1.json` to be testable, and **nothing regenerates it** — a
local KSP run emits only the version currently declared. Clearing `app/schemas/` to
"regenerate" it therefore deletes every earlier schema permanently: the build stays
green, `MigrationTestHelper` quietly stops proving anything, and git holds the only
remaining copy.

Written after exactly that happened. The check as it then stood **passed** on
`bug-migration-source-schema-deleted/` — three untestable migrations and a clean bill of
health — which is the proof the bug was invisible.

The rule is keyed on migrations, not on "1..N must all exist". A project that enabled
`exportSchema` at version 3 has no `1.json` by design and must not be nagged about it;
if it also carries a `Migration(1, 2)`, that migration really is untestable and the
failure is correct.

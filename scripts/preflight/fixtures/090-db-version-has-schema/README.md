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

# 130 — migration ADD COLUMN matches the exported schema

## The incident

Localmind schema v3 added `providers.model` as a Kotlin `val model: String = ""`.

SQLite rejects `ALTER TABLE t ADD COLUMN c TEXT NOT NULL` on a non-empty table, so
`MIGRATION_2_3` had to supply a default. But a **Kotlin default never reaches SQL**, so
Room's `CREATE TABLE` had none. The two sides disagreed:

```
migration: `model` TEXT NOT NULL DEFAULT ''
Room:      `model` TEXT NOT NULL
```

**Room tolerates this**, which is what makes it dangerous. Nothing throws. Nothing goes
red. A fresh install and an upgraded install simply end up carrying different table
definitions, and the first raw `INSERT` omitting the column works on one phone and
fails on another.

A persisted schema can only change forwards, by migrating data that already sits on
devices, so correcting this later means shipping a whole extra migration to fix a schema
that should never have differed.

## How it was found

Not by reading the code — both fragments look correct in isolation. It surfaced only
because the two SQL strings were compared **programmatically**: the migration's string
literal reconstructed from source, against `createSql` in the committed schema JSON.
That comparison is now this check.

Caught before v3 shipped, so the fix was one annotation
(`@ColumnInfo(defaultValue = "''")`) rather than a fourth migration.

## Why a regex is not enough

The migration SQL is a Kotlin expression, routinely split across lines and concatenated
with `+`. The comparison target is JSON. Both sides need real parsing, which is why the
check embeds Python rather than reaching for sed. A pattern that *mostly* reconstructed
the SQL would be a fourth near-miss in a corpus that already has three.

## Fixtures

- `bug/` — migration says `DEFAULT ''`, schema says no default. Verified: **all 12
  existing checks pass on this tree**, so the defect was genuinely invisible.
- `fixed/` — both sides declare `DEFAULT ''`.

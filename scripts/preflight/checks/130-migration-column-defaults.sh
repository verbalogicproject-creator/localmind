#!/usr/bin/env bash
# A migration's ADD COLUMN must define the column exactly as Room does.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="130-migration-column-defaults"
    TITLE="migration ADD COLUMN matches the exported schema"
    CATCHES="A hand-written migration whose column definition differs from the one Room
generates for the same entity. The usual cause is a DEFAULT: SQLite rejects

  ALTER TABLE t ADD COLUMN c TEXT NOT NULL

on a non-empty table, so the migration MUST supply a default -- while a Kotlin
default (val c: String = \"\") never reaches SQL, so Room's CREATE TABLE has none
unless @ColumnInfo(defaultValue = ...) says so.

Room TOLERATES that mismatch, which is exactly what makes it dangerous. Nothing
fails. A FRESH install and an UPGRADED install simply end up with different table
definitions, and the first raw INSERT omitting the column works on one phone and
fails on another. The persisted schema is one of three things that can never be
changed after release, so the correction is a whole extra migration.

Found by comparing the two SQL fragments programmatically rather than reading them,
which is the only way this is visible -- both look correct in isolation."
    SCOPE="room"
}
meta
ROOT="$(af_root "${1:-}")"

mapfile -t SRC < <(af_src_dirs "$ROOT")
[ ${#SRC[@]} -gt 0 ] || { pass "$TITLE (no sources)"; af_exit; }

mapfile -t MIGFILES < <(grep -rlE 'ADD +COLUMN' "${SRC[@]}" 2>/dev/null)
[ ${#MIGFILES[@]} -gt 0 ] || { pass "$TITLE (no ADD COLUMN migrations)"; af_exit; }

[ -d "$ROOT/app/schemas" ] || { pass "$TITLE (no exported schemas)"; af_exit; }

# Python rather than sed: the migration SQL is a Kotlin expression, routinely split
# across lines and concatenated with +, and the comparison target is JSON. Both
# sides need real parsing. A regex that "mostly" reconstructs the SQL would be
# another near-miss in a corpus that already has three.
findings="$(python3 - "$ROOT" "${MIGFILES[@]}" <<'PY'
import json, os, re, sys

root, files = sys.argv[1], sys.argv[2:]

# Every committed schema, newest version first: a column added in migration N->N+1
# is described by schema N+1 or any later one that still has it.
schemas = []
for dirpath, _dirs, names in os.walk(os.path.join(root, "app", "schemas")):
    for n in names:
        if not n.endswith(".json"):
            continue
        try:
            with open(os.path.join(dirpath, n)) as fh:
                db = json.load(fh)["database"]
        except Exception:
            continue
        schemas.append((db.get("version", 0), db.get("entities", [])))
schemas.sort(key=lambda s: -s[0])

def room_column(table, column):
    """Room's own definition of this column, from the newest schema that has it."""
    for _v, entities in schemas:
        for e in entities:
            if e.get("tableName") != table:
                continue
            sql = e.get("createSql", "").replace("${TABLE_NAME}", table)
            m = re.search(r"`%s`[^,)]*" % re.escape(column), sql)
            if m:
                return " ".join(m.group(0).split())
    return None

# A string literal, honouring backslash escapes so an escaped quote does not end it.
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
ADDCOL = re.compile(
    r"ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+(`?(\w+)`?[^;]*)",
    re.IGNORECASE,
)

problems = []
for path in files:
    with open(path, errors="replace") as fh:
        src = fh.read()
    # Reconstruct each execSQL argument. Concatenating literals per-call, rather
    # than per-file, keeps two separate statements from being glued together.
    for call in re.finditer(r"execSQL\s*\((.*?)\)\s*(?:,)?\s*(?:\n|$)", src, re.S):
        sql = "".join(LITERAL.findall(call.group(1))).replace("\\\"", "\"")
        m = ADDCOL.search(sql)
        if not m:
            continue
        table, coldef, column = m.group(1), m.group(2), m.group(3)
        coldef = " ".join(coldef.strip().rstrip(")").split())
        if not coldef.startswith("`"):
            coldef = "`%s`%s" % (column, coldef[len(column):])
        expected = room_column(table, column)
        rel = os.path.relpath(path, root)
        if expected is None:
            problems.append(
                "%s: migration adds `%s`.`%s` but no committed schema describes that "
                "column -- the schema for the new version is missing or stale"
                % (rel, table, column))
        elif expected != coldef:
            problems.append(
                "%s: `%s`.`%s` is defined differently by the migration and by Room.\n"
                "       migration: %s\n"
                "       Room:      %s\n"
                "       A fresh install and an upgraded install would carry different "
                "table definitions." % (rel, table, column, coldef, expected))

print("\n".join(problems))
PY
)"

if [ -n "$findings" ]; then
    while IFS= read -r line; do
        case "$line" in
            "       "*) note "${line#       }" ;;
            *) fail "$line" ;;
        esac
    done <<< "$findings"
else
    pass "$TITLE"
fi
af_exit

# 050-androidx-artifacts

**Incident:** `androidx.lifecycle.compose.collectAsStateWithLifecycle` with only
`lifecycle-runtime-ktx` declared. One root error produced FOUR cascade errors,
including a type mismatch against `Int` that appears nowhere in the source — the
failed import made the symbol an error type and everything touching it reported
nonsense.

`bug-loose-match/` is this corpus's **second near-miss failure**: an alternation
matching `lifecycle` OR `compose` passes on any project declaring `activity-compose`,
so the check reported success with the bug present. The pattern must require both
segments joined: `lifecycle-[a-z-]*compose`.

# 140 — targetSdk meets Play's submission requirement

## The incident

On 2026-08-15, with 16 days to spare, the pipeline was found to pin `compileSdk 34` in
its templates and its version matrix. From **31 August 2026** Google Play refuses any
new app or app update targeting below API 36.

So **every app this pipeline had ever generated, and every app it would generate, was
unsubmittable** — and nothing anywhere said so.

That is the shape of the failure worth understanding. The app compiles. It signs. It
installs. It runs. It passes all thirteen other checks. It is refused at *upload*,
which is the one place this pipeline had no visibility into. The verification ladder
runs from static checks to a physical device, and distribution sits past its top rung.

The cost was not the one-line bump either. AGP 8.3's ceiling is API 34, so reaching 36
moved AGP, Gradle, Kotlin, KSP, Hilt and Room together — a lattice change that took
several CI round trips and a reversed decision.

## Why the check warns about itself

A hardcoded requirements table is config with a canonical upstream, which makes it
exactly the thing this project distrusts. Google raises the minimum about once a year;
a table verified two years ago silently under-enforces, and **an under-enforcing check
is worse than no check because it still occupies a row on a coverage table.**

So the check carries `TABLE_VERIFIED` and says when its own knowledge has aged past the
cadence at which the requirement moves. It reports that as a note, not a failure — being
out of date is not the project's bug; pretending to enforce something last checked a
year ago is.

## Why it warns 120 days out

A deadline announced a week ahead is a deadline missed. A target-SDK bump usually drags
the whole build lattice with it, which is days of work rather than minutes — so the
warning fires while acting on it is still cheap.

## Fixtures

- `bug/` — `targetSdk 34`. Verified: **all 13 existing checks pass on this tree**, so
  the defect was genuinely invisible to the corpus.
- `fixed/` — `targetSdk 36`.

Note `fixed/` passes with the message *"required 35"*, because on the date of writing
the 36 requirement had not yet taken effect. The check enforces what is in force and
*warns* about what is coming, which is the distinction that makes it actionable rather
than alarming.

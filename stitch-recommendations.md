# Stitch review — UI/UX ideas worth taking

Source: `/storage/emulated/0/Download/stitch_amber_foundry_ecosystem/`, the 12
`localmind_*` screens. The 17 `studio_*` screens are Knowledge Studio and out of scope.

**Visual reference only.** No generated HTML, CSS, JavaScript, CDN dependency, Google
Font, or mock datum is copied. What follows is information architecture and interaction
patterns, separated from the fabricated content they are drawn with.

**The separation matters more than usual here.** These mockups are dense with numbers
that were invented by a design tool — RAM figures, throughput, context sizes, accelerator
names — and they sit in exactly the places a user reads as measured fact. The good ideas
and the fabrications are interleaved, not segregated, so each is judged individually
below rather than by screen.

---

## Adopt now — no contract needed

### 1. Bottom navigation has icons

`icon = {}` in `AppNavHost.kt` — the bar is text-only. Every mockup pairs an icon with
its label. This is not decoration: an icon gives the bar a scannable shape at a glance
and a second, non-textual cue for the selected tab, which matters when the only current
distinction is colour plus a small pill.

**Cost:** three Material icons. **Risk:** none. Labels stay, so nothing depends on icon
literacy.

### 2. A one-line subtitle under each screen title

"Manage local inference engines and external API connections." sits under *Models &
Providers*. Our screens open straight into content. A subtitle costs one line and answers
"what is this screen for" without a tour.

Ours would say what is true: *"Endpoints this app can talk to. It starts none of them."*

### 3. Inline monospace spans in assistant answers

The mockup renders `ExpertManager.ts` and `verifyPackIntegrity(expert.packId)` as inline
code inside prose. Our chat renders answers as flat text, so identifiers run together with
the sentence around them. Given the models here are used for code questions — Qwen3.5 is
seeded as *"Code and structured output"* — this is a real legibility gain on the path
that already works.

**Scope:** inline spans only. Not full Markdown. A model emitting a table or a heading is
not a reason to build a Markdown renderer.

### 4. The `id:` line on any entity card

Expert cards show `id: kf_core_env` beneath the display name, in monospace. That is the
pattern I already reached for independently on Providers rows (`127.0.0.1:8090 · lfm-8b`)
after finding three endpoints indistinguishable. Worth stating as a house rule: **where a
stable id exists, show it under the label.** The label is for humans, the id is what the
system acts on, and confusing the two is how a rename silently repoints a selection.

### 5. A left accent bar for the active row

The mockup marks active and updatable cards with a coloured left edge. We already avoid
colour-only signalling with `●`/`○` marks; an accent bar is a second, larger cue that
survives being glanced at. Additive, not a replacement.

---

## Adopt when the contract allows — designed now, built later

### 6. The evidence summary card, above the answer

The strongest idea in the set. Before the prose, a bordered card carries:

- a **disposition** — "Sufficient evidence"
- a **count** — "6 evidence items from 2 experts"
- **which experts** — one chip each, with version
- a single **"Open evidence"** action

This is the right shape because it puts the grounding claim *before* the text it
qualifies, where a reader meets it first. Our current design opens evidence from a
message; this makes the disposition legible without opening anything.

**Blocked on:** `canonical-assistant-turn`, planned-not-implemented. **When built:** the
disposition must come from the receipt, never from prose, and "Sufficient" is a word the
Harness must say — not a threshold this client applies to a count.

### 7. Filter chips on the Expert Library — partially

`All / Active / Installed / Updates`. Three of the four are expressible today:
`mount_state` is closed to `active` and `installed-inactive`, so All/Active/Installed map
cleanly.

**"Updates" is not implementable and should not be built.** Nothing in
`expert-release-summary/3.0` carries predecessor or successor information, so an update
badge would have to be inferred from two versions existing — which is exactly the kind of
derived claim this project refuses. The mockup also puts a notification dot on that chip;
a dot asserting pending updates we cannot detect is worse than the chip.

### 8. The revoked-pack treatment

A revoked entry is rendered with a struck-through title, a warning line, and **only a
Remove action** — no activate, no inspect-and-activate path. That is the correct shape,
and it should exist in the code before it can ever be reached.

Note it is currently **unreachable**: `trust_state` is a `const: "trusted"` in the schema,
so the catalog structurally never lists a revoked release. The styling is worth writing
anyway, guarded, so the day the contract widens the safe rendering already exists rather
than being authored under pressure.

### 9. Version transitions rendered as `v1.1.0 → v1.2.0`

Good, once there is a predecessor to show. Same blocker as (7).

### 10. Client-side search on the library

A search icon in the header. Purely local filtering over names and ids — no query goes to
the Harness. Worth having once there is more than a handful of packs; pointless before.

---

## Reject, with reasons

### Fabricated measurements — the whole class

*Models & Providers* displays `CONTEXT 8,192 tokens`, `SPEED 24.4 t/s`, `MEMORY 4.8 GB
RAM`, `COMPUTE Qualcomm Hexagon / NPU-capable`, and a `LOADED` / `Q4_0` chip pair.

**None of these was measured.** They are the single most dangerous thing in the archive,
because they are rendered in the same monospace, in the same panel, as the values we do
observe — so a user cannot tell which figures are real. We already show `tok/s` **only**
from an actual completion's timings, and context size **only** when a server reported one.
That distinction is the point, and adopting this layout wholesale would erase it.

Note the same trap arrived from a second direction today: llama-swap's own
`description` fields read *"MEASURED AT 2.8 tok/s"* and *"~24 tok/s"*, which are strings
someone typed into a YAML file. They are not modelled, deliberately.

### Cloud and Hybrid operating modes

`LOCAL / CLOUD / HYBRID` with "Smart routing between local & cloud". Cloud providers are a
hard exclusion, and "hybrid routing" would send user text off-device — the one promise the
setup screen makes by name.

The *pattern* underneath is worth keeping though: an unavailable option shown with a
status chip and a stated reason, rather than hidden. That is precisely how capability
gating already works here, and it is the same argument that made the Experts tab tappable
instead of disabled.

### "Eject Model"

Model lifecycle control. Localmind starts nothing and stops nothing; llama-swap owns
residency and loads on demand. A button implying otherwise would misrepresent where
control lives — and `unloaded` is a resting state, not a problem to fix.

### "Import .kpack" floating action button

Pack installation and parsing. Excluded outright, and `localmind-never-parses-or-versions-kpacks`
is a stated rule in `api-bindings.json` — not merely a scope limit.

### The attachment paperclip in the composer

Document ingestion. Out of scope, and it would imply the app can add knowledge, which is
Studio's job.

### The Wi-Fi-off icon in the chat header

It reads as a claim about network state that nothing in the app measures, and it sits
beside genuine status. If offline-ness is worth signalling, it should come from an
observed connectivity check — otherwise it is decoration that looks like telemetry.

### "Settings" as the third tab label

The mockup's third tab is *Settings*; ours is *Providers*. Ours is more honest — the
screen manages endpoints, not application preferences — and renaming it would promise a
settings surface that does not exist.

---

## One thing the mockups get right that is easy to miss

Every expert card states its trust status **as a line of text with an icon** — "Trusted
signature" — rather than as a colour or a badge alone. For the one property where being
misread is most costly, they spend a full line. That instinct is correct and matches the
existing `MARK_*` convention, and it is worth preserving when the library is finally
populated: **trust is never a colour.**

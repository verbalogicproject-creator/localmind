# Localmind — task tracker

Working tracker for the Amber shell + Knowledge Foundry consumption work.
Branch `feat/amber-seven-surface-shell`. Updated as work lands.

**Legend** — `[x]` done and verified · `[~]` done, verification pending · `[ ]` not started
· `[!]` blocked, blocker named.

---

## Done and verified

- [x] **Amber seven-surface shell** — 7 routes, no deep links, no exported activities
- [x] **Setup, Experts tab, Providers, Chat header** — the four UX contradictions
- [x] **`libomp.so` packaging** — bundled from the runner's NDK, digests re-pinned;
      static `DT_NEEDED` verification now runs in CI across all four ABIs
- [x] **14 test defects** — 7 could never pass, 3 could never fail, 4 surfaced by CI
- [x] **Session state machine** — `Connected → Refreshing → Connected`, proactive refresh,
      no persistence, four read-only scopes enforced by the enum
- [x] **`/3.0` negotiation + strict decoders** — capabilities, catalog (empty AND
      populated), release detail, token exchange and refresh, all from server goldens
- [x] **Expert Library** — All/Active/Inactive, local search, trust as a text line, six
      states, no mutating controls; identity abbreviation computed for uniqueness
- [x] **Expert Detail** — every contracted field, progressive disclosure, full copyable
      digests
- [x] **Polish** — nav icons, factual subtitles, backtick-only inline code, ephemeral
      server model names, active-row accent
- [x] **Transport** — Ktor loopback client, session repository, real capability source,
      pairing panel mounted in Setup and Experts
- [x] **Live-contract corrections** — pairing frame, exact inspect body, release-id
      routing, capability race
- [x] **CI on this branch** — `ci.yml` green; `navigation-compose 2.9.8` validated on
      x86_64 at last

## First physical-device session — OBSERVED 2026-08-17

Pairing, catalog and detail worked on the NX779J against a live Harness. Recorded from
two screenshots of the Expert Detail surface; nothing below is inferred.

- **Expert Detail rendered live data.** `kf:pack:c597b1bf…` matches the mounted Project
  Expert. The release identity `kf:pack-release:3c2c714a…` **differs from the golden's**,
  which is how we know this is a real response and not a fixture.
- **Reaching detail proves the library rendered a non-empty catalog** — the branch that
  crashed. The nested-scroll fix holds on a real device.
- **Fields observed:** name, `namespace/slug · version`, description, Active, "Trusted
  signature, verified by Knowledge Foundry", compatibility `accepted`, profile
  `project-companion`, risk class `moderate`, publication channel `development`,
  capabilities, allowed sensitivities `internal`.
- **Progressive disclosure works.** Identity and Verification expand; pack, release and
  install identities and four SHA-256 digests render **full length, wrapping across two
  lines, never truncated** — which is what that surface was built to do.
- **Proactive rotation works, observed 2026-08-17.** Still `Connected` after six minutes
  on screen, and still `Connected` after a further six minutes spent on Chat. The token
  is minted for 300 seconds, so surviving past five minutes is only possible if it was
  actually replaced — **at least one rotation completed**, unattended.
  Surviving the navigation leg separately proves the loop is no longer owned by a screen.
- **Not yet observed:** the library list itself, filters, search, a `Pair again` path,
  behaviour under long backgrounding or doze, and any retrieval.

## Verification status

| rung | state |
|---|---|
| JVM unit | **170 run, 0 failed** |
| lint · preflight | clean (15 checks) |
| `ci.yml` | green on `a2c56e3` |
| emulator API 28 + 36 | **108 tests green on `829c021`** |
| release APK | builds, 23.4 MB unsigned, all `DT_NEEDED` resolve |
| **physical device** | **pairing → catalog → detail observed working, 2026-08-17** |

## Blocked — Knowledge Foundry side

- [x] **First live session** — pairing, catalog and detail observed on the device.
- [!] **Grounded evidence** — `canonical-assistant-turn` planned-not-implemented.
- [!] **Tool approval** — `governed-tool-proposal-decision-receipt` planned-not-implemented.

## Not started

- [ ] **Evidence summary card above the answer** — designed in
      `stitch-recommendations.md`, blocked on the assistant-turn contract
- [ ] **Signing / tagging** — only after Experts works end to end on a real Harness

---

## Open divergence, deliberate

`docs/ui/route-manifest.json` still declares `experts/{packId}/{version}`; the app routes
on `experts/{releaseId}` because the live adapter keys the lookup by release. The manifest
conformance test names this single exception rather than being loosened, so it still
catches any other drift — and it will fail again if either side moves. Expected to close
when the manifest follows.

## Standing constraints

- Localmind **consumes**; Studio creates, reviews, evaluates, signs.
- Scopes are exactly `capabilities:read`, `expert:read`, `query:read`, `token:refresh`.
- **No fabricated fixtures.** An id enters `SchemaNegotiation.ACCEPTED` only alongside a
  decoder *and* a server-emitted golden, in the same change.
- Never persist the pairing credential or the access token.
- Version incompatibility is never presented as a pairing failure.
- Foundry is `127.0.0.1:8091`; llama-swap is `8090`. Visibly separate services.

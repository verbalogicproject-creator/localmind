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
| JVM unit | **225 run, 0 failed** |
| lint · preflight | clean (15 checks) |
| `ci.yml` | green on `4daf986` |
| emulator API 29 + 36 | **118 tests green on `68a59c3`** |
| release APK | builds, 23.4 MB unsigned, all `DT_NEEDED` resolve |
| **physical device** | **pairing → catalog → detail → RETRIEVAL observed, 2026-08-17** |

## Blocked — Knowledge Foundry side

- [x] **First live session** — pairing, catalog and detail observed on the device.
- [x] **Live retrieval** — `query.retrieve` observed end to end on the device.
- [!] **Grounded answers** — `canonical-assistant-turn` planned-not-implemented. THE ONLY
      BLOCKER on the next shared milestone. Admit the schema only alongside its decoder,
      the exact golden digest, the request shaper and tests, in one change.
- [!] **Tool approval** — `governed-tool-proposal-decision-receipt` planned-not-implemented.

## Live retrieval — OBSERVED 2026-08-17, HEAD `68a59c3`

Query "memory" against the mounted Project Expert returned 8 evidence items,
`answerability: supported`, with a full receipt. Read from device screenshots; digests
are NOT transcribed here, because a digest copied by eye off a photograph is exactly the
kind of record that looks authoritative and is wrong.

Two defects that only a live pack could expose, both fixed:

- **Omissions rendered as a wall of hex, above the evidence.** Ten raw `kf:candidate:`
  identities, one per status line, between the user and what they asked for. Now counted
  in a sentence, placed after the items, identities behind a disclosure.
- **One evidence item can be an entire source file.** The per-item ceiling is 8,000 bytes,
  so eight items is hundreds of screens. Quotations now fold at 12 lines, state the real
  total, and open on request. FOLDING IS NOT SUMMARISING — nothing condensed, nothing
  elided from the middle, and the wording stays distinct from the Foundry's own truncation.

## Foundry-confirmed contract facts — 2026-08-17

Answers to the questions raised from the live run. Recorded because several are things
the schemas alone do not say.

1. **The query body is `{"request": <query-request/2.0>}`.** Canonical, going into the
   route SOT. The bare form is what the golden's echoed `plan.request` suggests and it
   returns `unknown-field`.
2. **`truncation.boundaries` being empty is a Foundry BUG, not a design.** Keep using
   `omissions` as the live signal, and keep rendering non-empty boundaries — verified
   still present at `RetrievalEvidenceView.kt` (the "Limited by …" line).
3. **Empty `selected_text` is valid** for graph-reached identity/provenance-only evidence.
   The explicit no-quote presentation is confirmed correct.
4. **The runtime ignores client `limits` and enforces fixed maxima**: 8 items, 8,000 bytes
   per item, 24,000 total. Keep sending `limits: {}`. NO kind-aware budgets until a
   corrected contract and golden arrive — the schema currently advertises a client-selected
   limit the runtime does not honour.
5. **Oversized evidence is omitted WHOLE, never partially truncated.** Never reconstruct
   it and never ask a model to complete it.
6. **Semantic and reranker channels are intentionally unavailable** on this stable
   deployment. Do not advertise them; do not add fallback behaviour.
7. **Do not generate or label grounded answers.** Evidence remains evidence.

## v0.1.0 RELEASE CANDIDATE — scope frozen 2026-08-17

Frozen at the Stage 3D grounded-turn slice. No AesCoder, Builder Canvas, navigation,
provider, or tool-authority additions enter v0.1.0.

**Proven on the physical device:** pairing → catalog → detail → retrieval → LFM generation
→ assistant-turn finalisation → receipt-backed grounded display. Observed with qwen-4b
against the mounted Project Expert; citation closure passed server-side.

**Version is a BUILD-TIME property, not a source constant.** `versionName`/`versionCode`
default to `0.0.1-dev`/`1` and are supplied by `release.yml` from the tag, so freezing the
scope required no source change and the tag alone determines the released identity.

### Release blockers

1. **No capability discovery for `assistant.turn.finalize`.** `capabilities_v3()` does not
   list it and `capabilities/4.0` is unreachable over HTTP. Localmind gates the action on a
   successful retrieval, which works and is an inference from a related capability rather
   than a declaration. Waiting on the corrected contract and golden. TAG BLOCKED ON THIS.
2. **The grounded-turn path has never run under R8.** Instrumented tests run against the
   debug variant (`testBuildType` is deliberately not `release`), and the release rung is a
   launch smoke: install, start, survive six seconds. Minification is on for release, so
   serialization and the Harness decoders are unverified in the artifact users install.
3. **No local release keystore.** `assembleRelease` here produces
   `app-release-unsigned.apk`; CI signs from repository secrets.

### Evidence needed before tagging

- One grounded turn on a physical device from a SIGNED RELEASE build — the same flow
  already proven on debug, repeated on the R8 artifact. This closes blocker 2 and is the
  only evidence that cannot be produced in CI.
- The corrected capability-discovery contract plus a server-emitted golden, landed the same
  way every other id was: decoder, exact golden-byte test, request shaper and UI handling
  in one change.
- A green `ci.yml` and `emulator.yml` on the exact commit to be tagged.
- The answer receipt opened once on the device, confirming the four provider digests render
  full-length. It has only ever been asserted in tests.

## Holding

Waiting on `canonical-assistant-turn` (schema + server-emitted golden). Until it lands:
no tag, no release, no new surfaces. Explicitly out of scope by direction — AesCoder,
Builder Canvas, sandbox, file creation, new providers, model configuration, and further
navigation work; those return only with a closed Android-facing contract.

Preserved as-is: strict decoders, session lifecycle, physical-device behaviour, and the
read-only authority boundary.

## Not started

- [ ] **Evidence summary card above the answer** — designed in
      `stitch-recommendations.md`, blocked on the assistant-turn contract
- [ ] **Signing / tagging** — held. Not before the next shared milestone.
- [ ] **NEXT SHARED MILESTONE** — one honest grounded LFM answer tied to an immutable
      evidence packet and receipt. Blocked on `canonical-assistant-turn`.

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

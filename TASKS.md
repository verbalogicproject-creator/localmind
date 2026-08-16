# Localmind — task tracker

Working tracker for the Amber shell + Knowledge Foundry consumption work.
Branch `feat/amber-seven-surface-shell`. Updated as work lands.

**Legend** — `[x]` done and verified · `[~]` done, verification pending · `[ ]` not started
· `[!]` blocked, blocker named.

---

## Done and verified

- [x] **Amber seven-surface shell** — 7 routes, capability-gated, no deep links
- [x] **Setup contradiction fixed** — action derives from `provider`; "Change endpoint"
      when configured, "Choose an endpoint" when not
- [x] **Experts tab no longer inert** — `enabled` flag removed; tap navigates, destination
      names `mount.list`; TalkBack label extracted to `expertsNavLabel()` so it is testable
- [x] **Providers density + differentiation** — rows now carry `host · model`, because all
      three seeded endpoints share `127.0.0.1:8090` and llama-swap routes by model name;
      seeded notice compacted; `MODE_HARNESS` rows labelled `not a real backend`
- [x] **Chat header truncation** — already fixed in `1d32f16`; screenshots predated it by
      4 hours. Bounds assertion hardened with `useUnmergedTree`
- [x] **`libomp.so` packaging** — bundled from the runner's NDK, digests re-pinned,
      verified end to end
- [x] **14 test defects fixed** — 7 could never pass, 3 could never fail, 4 surfaced by CI
- [x] **Session state machine** — `Connected → Refreshing → Connected`, proactive refresh,
      no persistence, four scopes enforced by the enum
- [x] **`/3.0` negotiation + strict decoders** — capabilities and empty catalog, decoded
      from server-emitted goldens

## Done, verification pending

- [~] **Stage 3C integration** (`f17dda1` + working tree) — 95 JVM tests, lint, preflight
      all green locally. **Instrumentation not yet run** on API 28/36 for this change.

## Blocked — Knowledge Foundry side

- [!] **Populated Expert Library** — no server-emitted **non-empty catalog** golden.
      `ExpertReleaseSummary` is transcribed from its schema and has never met a real
      response. `Ready` is written, typed, and unverified.
- [!] **Expert Detail** — `expert-release-detail/3.0` has a closed schema but **no golden**,
      so it stays out of `SchemaNegotiation.ACCEPTED` and the screen refuses.
- [!] **Transport** — one-use Termux pairing credential and token exchange not yet
      available. No HTTP client is written; the state machine is driven only by tests.
- [!] **Grounded evidence** — `canonical-assistant-turn` planned-not-implemented.
- [!] **Tool approval** — `governed-tool-proposal-decision-receipt` planned-not-implemented.

## Not started

- [ ] **Transport layer** — Ktor client emitting `Knowledge-Foundry-Accept-Schema: /3.0`,
      asserting `Cache-Control: no-store`, wired to the session machine. Waits on pairing.
- [ ] **`ci.yml` on this branch** — has never run here; `navigation-compose 2.9.8` is still
      unvalidated on x86_64
- [ ] **Device verification** — needs a public hotspot; see `appfactory-updates-log.md` #11

---

## Standing constraints

- Localmind **consumes**; Studio creates, reviews, evaluates, signs.
- Scopes are exactly `capabilities:read`, `expert:read`, `query:read`, `token:refresh`.
  No `mounts:write`, activation, pack parsing, or install-record access.
- **No fabricated fixtures.** An id enters `SchemaNegotiation.ACCEPTED` only alongside a
  decoder *and* a server-emitted golden, in the same change.
- Never persist the pairing credential or the access token.
- Version incompatibility is never presented as a pairing failure.

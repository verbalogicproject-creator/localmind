# Localmind — status handoff for Codex

**Date:** 2026-08-17
**Repo:** `/root/projects/localmind`
**Branch:** `feat/amber-seven-surface-shell`
**HEAD:** `ef2a53f` — fully pushed, branch in sync with `origin`
**CI status:** `ci.yml` and `emulator.yml` both **green on `ef2a53f`**
**Product state:** v0.1.0 release candidate, scope frozen at `297321a`. **Not tagged.**

---

## 0. What this project is, in one paragraph

A Jetpack Compose Android client for the Knowledge Foundry. It does one complete,
receipt-backed thing: pair → list installed experts → inspect a release → retrieve evidence
→ have a local model (llama-swap/LFM on `127.0.0.1:8090`) draft an answer from *only that
evidence* → have the Foundry finalise the turn and close a receipt over it → display the
answer as grounded, with a citation on each claim. The word "grounded" appears on exactly
one code path, and that path has a server-closed receipt behind it. It is not an agent: it
reads a declared surface and writes nothing.

---

## 1. Build environment (read this before running anything)

Everything runs **on the phone**: PRoot Ubuntu inside Termux on a Nubia NX779J
(SM8650 / Adreno 750, Android 15, API 35).

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
cd /root/projects/localmind
```

| | |
|---|---|
| AGP / Gradle / Kotlin | 9.0.0 / 9.4.0 / 2.3.0 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 28 |
| SDK | `/root/android-sdk`, build-tools `36.0.0` |
| adb | `/root/android-sdk/platform-tools/adb` (has mDNS; the Termux one does not) |

### ⚠ aapt2 version — read this before trusting any local release APK

`~/.gradle/gradle.properties` overrides aapt2, because AGP downloads an x86_64 ELF from
Maven and this device is aarch64. **Which aarch64 build it points at matters, and this cost
an afternoon:**

```
# was: /root/android-sdk/build-tools/36.0.0/aapt2   aapt2 2.19-20250916.230514   BROKEN
# now: /data/data/com.termux/files/usr/bin/aapt2    aapt2 2.20-android-16.0.0_r4  WORKS
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

Under **2.19** the debug path is fine, but on the **release** path (`isMinifyEnabled` +
`isShrinkResources`) the packaged APK comes out with dex, native libs and assets and **no
`AndroidManifest.xml`, no `resources.arsc`**. It is a structurally valid zip that no tool
can read — `aapt2` says *could not identify format of APK*, `apksigner` says *Missing
AndroidManifest.xml*, and the device rejects it as
`INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION`, an error naming the manifest and so reading
like an app bug rather than a toolchain one.

The shrunk resource archive AGP produces is **correct** even under 2.19
(`app/build/intermediates/shrunk_resources_binary_format/release/**/*.ap_` holds both
files); only the final merge loses them. Silent, late, and looks like your fault.

**Fixed as of this session** — `pkg install aapt2` in Termux, then point the override at it.
Verified: release APK 140 entries with manifest and `resources.arsc`, parses, signs;
debug and androidTest APKs unaffected.

- **It was a VERSION bug, not an architecture one.** Both binaries are aarch64. An earlier
  version of this document blamed the architecture; that was wrong.
- **CI never hit this** (x86_64, AGP's own aapt2), so the shipped artifact was never at risk.
- **Historical caveat:** an earlier report line — *"assembles cleanly, 40 native libs, every
  `DT_NEEDED` resolves"* — was measured on an APK that was missing its resources entirely.
  That check only inspected native libraries. Discount any local release-build claim made
  before this fix.

If `AndroidManifest.xml` ever goes missing from a release APK again, **check the aapt2
version first.** `<scratch>/r8-clean.sh` hard-fails with that message rather than proceeding.

---

## 2. Test and CI state

| rung | result | commit |
|---|---|---|
| JVM unit | **295 passed, 0 failed** | `ef2a53f` |
| Android lint (debug) | clean | `1b2b90d` |
| Static preflight (`scripts/preflight.sh`) | clean, 15 checks | `ef2a53f` |
| `ci.yml` | **success** (run `32029821647`) | `ef2a53f` |
| `emulator.yml` | **success** (run `32029825084`) | `ef2a53f` |
| Instrumented, emulator API 28 / 36 | **133 / 132 passed, 0 failed** | `ef2a53f` |
| Release launch smoke | passed | `ef2a53f` |
| Instrumented, **physical device** (debug) | **OK (132 tests), 0 failed** — but see caveat | ≈ `1b2b90d` |
| Instrumented, **physical device** (release/R8) | **not achieved** — see §4 | — |

> **Caveat on the physical-device green.** That run predates `ecb6b93`, so it is *not*
> evidence about the current tree. Carrying it forward is how the `CapabilityGateTest`
> regression in §3.1 reached `origin`. Re-run it before citing it.

**Neither workflow runs on a push to this branch.** `ci.yml` triggers on `main` + PRs;
`emulator.yml` on `main`, tags, and dispatch. A silent green branch means *nothing ran* —
dispatch explicitly after every push:

```bash
gh workflow run ci.yml       --ref feat/amber-seven-surface-shell
gh workflow run emulator.yml --ref feat/amber-seven-surface-shell
```

---

## 3. Commits made this session

All pushed; branch in sync with `origin`.

| commit | what |
|---|---|
| `2cca4ea` | v0.1.0 RC polish (4 items) + on-device model folder |
| `77e9570` | `v0.1.0-completion-report.md` |
| `5312f20` | Shortened the no-model status text |
| `1b2b90d` | Recorded green CI/emulator in the report |
| `ecb6b93` | Test source-set fix + `-PinstrumentRelease` flag |
| `374a8b8` | R8 instrumentation keep rules + diagnosis |
| `79cf00f` | This handoff |
| `ef2a53f` | **Fixed two regressions `ecb6b93` introduced** — see §3.1 |

### 3.1 `ef2a53f` — two traps worth knowing before you edit anything

`ecb6b93` was pushed without re-running the gates and broke both. Both are cheap to hit again.

**Trap 1 — a comment can fail preflight check 020.**

```
FAIL the build references 'instrumentRelease' but no such file exists
```

`scripts/preflight/checks/020-build-referenced-files.sh` slurps the whole build file and
matches `\w*[Pp]roguardFiles?\s*\((.*?)\)\s*$` with `/gms`, harvesting every quoted string
in the capture. So writing that call's name **followed by an opening paren** anywhere in
`app/build.gradle.kts` — *including inside a comment* — opens a match that runs to the next
`)` at end of line and treats every string in between as a path. A doc comment mentioning
it swallowed `hasProperty("instrumentRelease")` and reported it as a missing file.

The check is not wrong; it cannot know which literals are paths. **Do not add an exception
to it.** Name that function without parentheses in prose, and keep property lookups out of
its argument list — `instrumentRelease` is read into a val at the top of the file for
exactly this reason, with a warning comment attached.

**Trap 2 — a stale green is not evidence.**

`CapabilityGateTest:186` asserts the fixture's `action` string literally. `ecb6b93` moved the
fixture to `androidTest` *and* changed its text from `(preview fixture)` to `(test fixture)`
in one change, leaving the assertion behind. The physical-device run that passed 132 tests
predated the move, and citing it as if it covered the new code is what let this reach
`origin`. **If you change a fixture's text, grep for the literal:**

```bash
grep -rn "EXAMPLE action" --include=*.kt app/src
```

Note there are deliberately **two** copies of this fixture — `androidTest` for tests,
`src/debug` for Compose previews — with different wording. That duplication is intentional
(see §3 `ecb6b93`); do not "DRY" them back together, or the suite re-pins to one build type.

Always run before pushing:

```bash
bash scripts/preflight.sh
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

### `2cca4ea` — RC polish (all inside frozen scope)

1. **Answer is copyable.** `asCopyableText()` in `ui/evidence/GroundedTurnModels.kt` restores
   the `[1]` citation markers the parser strips and appends model / template / receipt id /
   `answer_sha256`. The **question is deliberately not copied** (clipboard is world-readable;
   `request_sha256` already binds it).
2. Search field labelled `"Question"`, not a repeat of the section heading.
3. **The question is named above the answer**, on grounded *and* not-grounded turns —
   `GroundedTurnUiState.Grounded`/`NotGrounded` now carry `question: String`.
4. **Prompt rule 1 is now "one claim per paragraph", stated first.** A paragraph *is* a
   segment — the unit the receipt cites — and qwen-4b was answering in one block, collapsing
   three citations into one segment.

**Contract-visible:** item 4 changes `template_sha256`. `TEMPLATE_ID` was bumped in the same
commit and both are pinned by a tripwire test.

| | template_id | template_sha256 |
|---|---|---|
| before | `localmind/grounded-turn/1.0` | `49a1a629…081ccf0d` |
| after | `localmind/grounded-turn/1.1` | `57a73656…1a273434` |

`GroundedTurnPromptTest.the_template_identity_is_pinned_to_its_bytes` fails deliberately on
any future prompt edit and its message says to bump the id in the same change. **Do not
"fix" it by updating only the digest.**

### `ecb6b93` — the real latent bug

`CapabilityGateTest` called `com.verbalogix.assistant.debug.fakeToolProposal()`, a fixture in
`src/debug`. `src/debug` is not compiled for the release variant, so
`connectedReleaseAndroidTest` died in the Kotlin compiler (`Unresolved reference 'debug'`)
before a single test ran. **A test reaching into another variant's source set pins the whole
suite to one build type.** Fixture moved to
`app/src/androidTest/java/com/verbalogix/assistant/ui/ToolProposalFixture.kt`.
`DebugFixtures.kt` keeps its own copy for Compose previews (legitimate use of `src/debug`).

Also adds, off by default:

```kotlin
testBuildType = if (project.hasProperty("instrumentRelease")) "release" else "debug"
```

---

## 4. The R8 story — read this before touching `testBuildType`

`app/build.gradle.kts` carried a long note concluding that release instrumentation **hangs**
("no output, no *Starting N tests*, until the 45-minute job timeout", run `31875004036`) and
that behavioural testing of the minified variant is therefore unachievable.

**It is not a hang. It is a crash**, visible via `adb shell dumpsys dropbox --print`:

```
java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;
  at androidx.test.runner.AndroidJUnitRunner.onCreate(...)
Caused by: java.lang.ClassNotFoundException: androidx.tracing.Trace
```

`AndroidJUnitRunner` runs inside the **app's** process and resolves against the **app's**
classes. R8 correctly deletes `androidx.tracing.Trace` because the app never calls it — but
the runner does, in `onCreate`, before reporting anything. The process dies before printing
`Starting N tests`, which from Gradle's side is indistinguishable from a hang.

Fix is in `app/proguard-instrument-release.pro`, applied **only** under `-PinstrumentRelease`
so shipped minification is untouched.

**The keep list did not stay small, and that matters.** After `androidx.tracing.Trace` came
`kotlin.LazyKt` — structural, because the test APK carries no Kotlin runtime of its own and
resolves against the app's. The file now keeps `kotlin.**` and `kotlinx.coroutines.**`.

> **A green run under `-PinstrumentRelease` means "R8 did not break Localmind's code". It
> does NOT mean "the shipped APK is verified".** The tested APK retains the whole Kotlin
> runtime. The launch smoke and the mapping-file assertions remain the checks that speak
> about what users install.

### Confirmed vs unconfirmed

- ✅ The crash diagnosis, from a real dropbox stack.
- ✅ **R8 does not break the app.** The minified build launches cold in **256 ms** with
  `MainActivity` as `topResumedActivity`.
- ✅ The minified APK builds and signs correctly through AGP alone — 148 entries, manifest
  and `resources.arsc` present, 26.4 MB. **No APK surgery is involved any more.** An earlier
  pass merged resources back in by hand to work around aapt2 2.19; that workaround
  (`<scratch>/r8-pipeline.sh`) is obsolete and its output would be weaker evidence than
  AGP's own.
- ❌ **The suite has never completed against the minified build.** Every attempt died on the
  adb link, never on a test. This is the one open thread.

To finish it — one command, then connect a device within 25 minutes:

```bash
bash <scratch>/r8-clean.sh    # build → install → instrument, no surgery
```

It needs the throwaway keystore at `<scratch>/localmind-r8-test.jks` (store/key pass
`r8testing`, alias `localmind`). **Not committed, and must never be — regenerate it:**

```bash
keytool -genkeypair -keystore <scratch>/localmind-r8-test.jks \
  -storepass r8testing -keypass r8testing -alias localmind \
  -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=Localmind R8 Verification, O=Verbalogix, C=SE"
```

Signing key is irrelevant to what is being tested — R8's output does not depend on it — but
an unsigned APK cannot be installed, and `signingConfig` is `null` without one.

---

## 5. Device access (how to get onto the phone)

adb pairing is **already done and persists** — `~/.android/adbkey` and
`~/.android/adb_known_hosts.pb`. Never pair again.

What rotates is the **connect port**, on every Wireless-debugging cycle:

1. Settings → Developer options → **Wireless debugging** (needs a Wi-Fi interface up; the
   phone's own **Portable hotspot** is enough, and is far more stable than joining a network)
2. Read `IP address & Port` from the top line
3. `adb connect localhost:<port>`

`adb mdns services` returns nothing from PRoot (no multicast), so the port must be read off
the screen. **The link is the main obstacle in this environment** — it dropped roughly every
90 seconds on external Wi-Fi.

Practical notes:
- Do **not** run `connectedAndroidTest` on a flaky link: Gradle compiles for minutes before
  looking for a device, and the connection dies first. Pre-build, then `adb install` +
  `adb shell am instrument` directly (~1 minute).
- **Always install both APKs.** Skipping the install produced 68 `NoSuchMethodError` from a
  stale app/test APK mismatch that looks exactly like real test failures.
- Set `window_animation_scale` / `transition_animation_scale` / `animator_duration_scale` to
  `0` before running (emulators default to 0; a phone does not) and restore to `1` after.
- Logcat is filtered on this ROM — app lines never appear. Use
  `adb shell dumpsys dropbox --print` for crashes.

---

## 6. Release blockers

1. **No capability discovery for `assistant.turn.finalize`.** `capabilities_v3()` builds from
   `LOCALMIND_OPERATION_IDS`, which excludes it; `capabilities_v4()` is never called from
   `facade.py`, and `/v1/capabilities` at `/4.0` fails the `{"turn_request"}` key check.
   Localmind gates the action on a successful retrieval — works, but is an inference from a
   related capability rather than a declaration. **TAG IS BLOCKED ON THIS. It is the
   Foundry's to fix**, and lands with decoder + golden-byte test + request shaper + UI
   handling in one change.
2. **Grounded-turn path unverified under R8 in the shipped artifact.** Downgraded, not
   closed — see §4.
3. **No local release keystore.** CI signs from repository secrets.

Also open: `capabilities/3.0` reports `runtime_contract: 0.3.2` while the freeze doc and
`capabilities/4.0` say `0.3.3`. Localmind stays pinned to `0.3.2`; changing it breaks pairing
against the live deployment.

## 7. Evidence still needed to tag

- [ ] One grounded turn on a physical device from a **signed release** build.
- [ ] Corrected capability-discovery contract + server-emitted golden.
- [ ] Green `ci.yml` **and** `emulator.yml` on the exact tagged commit (dispatch manually).
- [ ] The answer receipt opened once on the device — only ever asserted in tests.

---

## 8. Hard constraints — do not violate

- **Never request, log, or echo a pairing credential or access token.** Diagnose from
  redacted status/error codes only. The pairing credential is one-use with a 60-second life.
- **Never persist** the pairing credential or the access token.
- Token scopes are exactly `capabilities:read`, `expert:read`, `query:read`, `token:refresh`.
  Do **not** add `mounts:write`, activation, raw install-record access, evaluation status,
  source-standing, or tool-approval behaviour.
- Query text is **memory-only**: out of Room, `SavedStateHandle`, logs, history, crash
  records and analytics.
- No reading `.kpack` archives or Foundry databases from Android. No second inference server
  on device.
- **Scope is frozen for v0.1.0:** no AesCoder, Builder Canvas, sandbox, file creation, new
  provider, model configuration, tool authority, or navigation additions.
- Admit a new schema **only** together with its strict decoder, exact golden-byte test,
  request shaper, and UI disposition handling, in one change. Use the checked-in schemas and
  goldens; do not transcribe or reinterpret them.

---

## 9. Open item outside the freeze

Models live at `/storage/emulated/0/models/local-mind` (three Q4_0 ggufs). `EmbeddedEngine`
now looks there first, but **cannot read it**: from API 30 a `.gguf` is not media, so only
`MANAGE_EXTERNAL_STORAGE` grants access, and this build does not request it. Deliberate — an
all-files grant is a real authority expansion and the decision is the owner's.

Note also that `EmbeddedEngine` is **CPU-only and cannot be configured otherwise**: ARM's
binding exposes `loadModel(path)` and `sendUserPrompt(text)` and nothing else — no backend,
offload, context or thread control. The GPU path is llama-swap on `:8090`, where OpenCL
offload is a `llama-server` launch flag on the phone, outside the APK. Serving models from
that folder with `-ngl 99` is a llama-swap config edit, not an app change.

---

# Addendum — 2026-08-18: the minified suite actually ran

Section 4 ended with the R8 instrumented suite still unproven. It has now been run, three
times, on a physical device. **This section is the part worth mining for the Kotlin
specialist**, because the mechanism generalises to every Android project and the symptom
never names the cause.

## 10. R8 × androidTest: the class-resolution model

**The rule.** `AndroidJUnitRunner` runs INSIDE the app's process. The test APK does not
bundle its own copy of the Kotlin runtime or the AndroidX libraries — it resolves them
against the **app** APK. So every class the *test harness* touches that the *app* never
calls is, from R8's point of view, unreachable and correctly deleted.

**The symptom hides the cause twice over.**

1. JUnit resolves a test class's method *signatures* by reflection before running anything,
   so a missing parameter type fails the **class**, not the test. You get
   `initializationError`, which names nothing.
2. The tests in that class are then never enumerated. Round 1 reported `Tests run: 25` for a
   135-test suite. **110 tests silently did not exist.** A count is not a pass, and a suite
   that shrinks looks identical to a suite that is fast.

### What three rounds actually found

| Round | Ran | Failed | Newly missing classes |
|---|---|---|---|
| 1 | 25 | 20 | `compose.runtime.Composer`, `sqlite.db.framework.FrameworkSQLiteOpenHelperFactory`, `MemoryStore`; + `VerifyError`: R8 finalized `NavHostController`, which `TestNavHostController` extends |
| 2 | most | 61 | `compose.ui.platform.InfiniteAnimationPolicy`, `sqlite.db.SupportSQLiteOpenHelper$Factory`, `navigation.NavArgumentBuilder`, `ui.tools.ToolProposal` |
| 3 | — | — | boundary moved from class to library; handed to CI |

**Naming individual classes does not converge.** Round 2 was not a shorter list than round 1,
it was a different one, bought for eight minutes of R8 rebuild. The harness reaches into
these libraries in ways nothing declares up front. Draw the keep boundary at the **library**
(`androidx.compose.**`, `androidx.sqlite.**`, `androidx.navigation.**`, `androidx.lifecycle.**`,
`androidx.activity.**`) and accept the stated loss of fidelity, or expect to enumerate.

### Two failure modes that look unrelated and are not

- `ClassNotFoundException` — R8 **removed** the class. Test APK names it, app APK lacks it.
- `VerifyError: Superclass z3.z of X is declared final` — R8 **finalized** the class, correctly,
  because nothing in the app subclasses it. Only the test APK does. A `-keep` on the superclass
  fixes it; a `-keep` on the subclass does not.

### The finding that justifies the whole rung

`MemoryStore` was deleted by R8 **correctly**. `app/src/main/java/.../data/memory/`
(`MemoryStore`, `Episode`, `Fact`, `DocPointer`) is referenced from no other file in
`app/src/main`. Four instrumented tests covered it; on debug they pass, because debug keeps
everything.

**Four passing tests over code that does not ship.** That is invisible to every unit test,
every lint rule, and every debug instrumentation run. Minification is what made it visible.
For the specialist: *a green instrumented suite on a debug build says nothing about whether
the code under test is reachable in the shipped artifact.*

Left as a keep rule with the decision stated rather than resolved — wiring it in or deleting
it with its tests is a product call, not a minification one.

### The correction that matters most

`scripts/emulator-verify.sh` carried, for months, the note that `connectedReleaseAndroidTest`
*"hangs indefinitely with no output (run 31875004036, both API levels, 45-minute timeout)"*.

**It never hung.** `AndroidJUnitRunner.onCreate` died on a `NoClassDefFoundError` for
`androidx.tracing.Trace` — R8 deleting a class the app never calls but the runner does — and
the process was gone before it could print `Starting N tests`. No output plus no exit is
indistinguishable from a hang, and reading it as one is why this project believed minification
could not be tested at all. **A crash before the first line of output is the single most
misdiagnosed failure in Android instrumentation.**

## 11. Where the rung now lives

`scripts/emulator-verify.sh` step 3, gated on signing secrets, wrapped in `timeout 1500`,
with `124` distinguished from a test failure because the remedies share nothing. Six steps
now, ordered by what they can prove: signing → app works (debug) → R8 didn't break it
(minified) → shipping APK builds → it launches → it upgrades.

Commit `bf0fb6b`. `emulator.yml` timeout raised 45 → 75 minutes; the 531s/130-run average in
its header now describes a floor, not an average, and is labelled as such.

**Read a green step 3 as "R8 did not break Localmind's own code."** Not as "the shipped APK
is verified" — the keep file holds the Kotlin runtime and the AndroidX UI libraries whole so
the harness can resolve against them. Steps 4–6 are what speak for the artifact users install.

## 12. Still open

- **The live grounded turn on a physical device.** Both servers were confirmed up on
  2026-08-18 (`8090` llama-swap fronting `llama-server --device GPUOpenCL -ngl 99`; `8091`
  Foundry answering `401` on `/v1/capabilities`). The discovery leg added in `1ff5336` has
  still never run against the live server.
- **The answer receipt opened once by a human.** Only ever asserted in tests.
- Device access remains the bottleneck: Wireless debugging rotated its port five times in one
  session and refused between each. `adb tcpip 5555` after any successful connect is the
  mitigation; moving the R8 rung to CI removed the only task that strictly required adb.

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

### ⚠ Local release builds are structurally broken on this machine

`~/.gradle/gradle.properties` contains:

```
android.aapt2FromMavenOverride=/root/android-sdk/build-tools/36.0.0/aapt2
```

Required, because AGP ships aapt2 as an x86_64 ELF and this device is aarch64. But on the
**release** path (`isMinifyEnabled` + `isShrinkResources`) that combination silently drops
the resource container: the packaged APK comes out with dex, native libs and assets but
**no `AndroidManifest.xml` and no `resources.arsc`**. The platform rejects it with
`INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION`.

- The **debug** path is unaffected.
- **CI is unaffected** (x86_64, AGP's own aapt2) — its release APK installs and launches.
- **Any local claim about release-build health on this machine is worth little.** An earlier
  report line ("assembles cleanly, 40 native libs, every `DT_NEEDED` resolves") was made
  against an APK that was missing its resources entirely. Treat CI as authoritative.

Workaround, if you need an installable local release APK — merge the (correct) shrunk
resource archive back in, align, re-sign. Scripted at
`<scratch>/r8-pipeline.sh`; the archive is at
`app/build/intermediates/shrunk_resources_binary_format/release/**/*.ap_`.

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
- ✅ The minified APK builds, repairs, aligns and signs (148 entries, manifest + arsc present).
- ❌ **The suite has never completed against the minified build.** Every attempt died on the
  adb link, not on a test. This is the one open thread.

To finish it:

```bash
bash <scratch>/r8-pipeline.sh     # build → repair → sign → install → instrument
```

Needs a connected device and the throwaway keystore at
`<scratch>/localmind-r8-test.jks` (pass `r8testing`, alias `localmind`) — **regenerate it,
it is not committed and must never be.**

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

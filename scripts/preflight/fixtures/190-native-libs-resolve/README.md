# 190-native-libs-resolve

## The incident

Localmind ships llama.cpp as prebuilt `.so` files. On the first instrumented run against
a physical arm64 device — the first time that suite had ever executed — it failed with:

```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libomp.so" not found:
    needed by /data/app/.../lib/arm64/libggml-base.so
```

Eight arm64 libraries declared `NEEDED libomp.so`. None of them shipped it.

ggml links against OpenMP on arm64 — upstream's Android `CMakeLists` enables it for that
ABI — and `libomp.so` belongs to the **NDK**, not to the build. Upstream never hits this
because their example builds through AGP, which collects the NDK's runtime libraries into
the APK on their behalf. A workflow that runs `cmake` directly and packages exactly what
`cmake` wrote drops the dependency silently. The libraries were correct all along; the
packaging was short one file.

Everything stayed green: the build, the unit tests, lint, R8, and every existing preflight
check. Only a device said otherwise.

## Why it is a static check

The defect is visible in the ELF header the loader itself reads. It needed no runtime, and
the emulator rung could never have found it — **no x86_64 library declares the dependency**,
because upstream enables OpenMP for arm64 only, and emulators here are x86_64.

Discovering it cost a device round trip, a CI republish and a security trust-anchor update.
Detecting it costs about a second, and covers `armeabi-v7a` and `x86` too — ABIs that
neither the test device (arm64) nor the emulator (x86_64) ever exercises.

## Fixtures

Real ELF objects, built small on purpose: `-nostdlib` so they declare no libc, and
`-Wl,-z,max-page-size=4096` because aarch64's default 64K alignment made otherwise-empty
files 66KB. They are ~5KB each.

| tree | contents | expected |
|---|---|---|
| `bug/` | `libmain.so` alone, declaring `NEEDED libdep.so` | exit 1 |
| `fixed/` | `libmain.so` and `libdep.so` together | exit 0 |
| `bug-wrong-abi/` | `libmain.so` in `arm64-v8a/`, `libdep.so` in `x86_64/` | exit 1 |

`bug-wrong-abi` is the evasion variant, and it is the one worth keeping. A check that asks
"does a file with this name exist in the repo?" passes it — the dependency **is** present,
in the wrong ABI directory. The loader searches one ABI directory and does not fall back;
a library built for another machine is not a substitute for a missing one.

## Step 2, recorded

Per `CHECKS.md`, the existing corpus was run against `bug/` **before** the check was
written, and reported:

```
Preflight clean (18 checks).
```

That is the proof the bug was invisible.

## What it does not do

It proves every dependency is **present**, not that the library **loads**. A missing symbol
inside a library that is present still gets through. That gap is far smaller than the one
this closes, and claiming otherwise would be the more dangerous error.

## One thing it caught in itself

The first version excluded corpus trees with an unanchored `-path '*/scripts/preflight/*'`.
A check's own fixtures live under that path, so every fixture pruned itself, the scan found
nothing, and all three trees "passed" — including `bug/`. The selftest caught it on the
first run. An exclusion meaning "inside the project being scanned" must be anchored to the
project being scanned.

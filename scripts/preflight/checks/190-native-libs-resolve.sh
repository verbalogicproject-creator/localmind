#!/usr/bin/env bash
# Every DT_NEEDED of every packaged native library resolves at load time.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="190-native-libs-resolve"
    TITLE="every packaged native library's dependencies are present"
    CATCHES="A shared library shipped without one of the libraries it links against.
The build succeeds, the APK installs, every static gate stays green, and the app dies
at the first System.loadLibrary with the linker's message:

  dlopen failed: library \"libomp.so\" not found: needed by libggml-base.so

THE INCIDENT. llama.cpp's ggml links against OpenMP on arm64 -- upstream's Android
CMakeLists enables it for that ABI -- and libomp.so belongs to the NDK, not to the
build. Upstream never notices because their example builds through AGP, which collects
the NDK's runtime libraries into the APK for them. A workflow that runs cmake directly
and packages what cmake wrote ships eight libraries declaring NEEDED libomp.so and no
libomp.so. Nothing anywhere says so until a device refuses to load it.

WHY THIS IS A STATIC CHECK AND NOT AN EMULATOR ONE. It cost a physical-device round
trip, a CI republish and a trust-anchor update to find, and the emulator rung could
NEVER have found it: no x86_64 library declares the dependency, and emulators here are
x86_64. This reads the same ELF header the loader will read, in about a second, and
covers armeabi-v7a and x86 as well -- ABIs that neither a test device nor an x86_64
emulator exercises.

It proves every dependency is PRESENT, not that the library LOADS: a missing SYMBOL
inside a library that is present will still get through. That is a much smaller gap
than the one it closes, and pretending otherwise would be the more dangerous claim."
    SCOPE="projects shipping .so files under an ABI directory"
}
meta
ROOT="$(af_root "${1:-}")"

# Prefer GNU readelf, accept LLVM's. Both print DT_NEEDED identically enough.
READELF=""
for c in readelf llvm-readelf eu-readelf; do
    command -v "$c" >/dev/null 2>&1 && { READELF="$c"; break; }
done
if [ -z "$READELF" ]; then
    # SKIP LOUDLY. A silent pass here is indistinguishable from a clean result, which
    # is the failure mode this corpus treats as worse than no check at all.
    pass "$TITLE (SKIPPED: no readelf on PATH -- install binutils to enable this check)"
    af_exit
fi

# Directories the loader would search: one ABI directory, and only that one. A library
# in a sibling ABI is not a fallback, it is a different machine's code.
#
# Fixture and corpus trees are excluded for the reason checks 010 and 110 learned the
# hard way -- a corpus that scans itself reports its own deliberate samples as defects.
#
# ANCHORED TO $ROOT, and that is not a detail. Written as the unanchored `-path
# '*/scripts/preflight/*'`, the pattern matches the absolute path of a check's OWN
# FIXTURES -- which live under scripts/preflight/fixtures/ -- so every fixture pruned
# itself and the check reported "no native libraries" on all three trees, passing the
# bug fixture. The selftest caught it immediately. An exclusion meant for "inside the
# project being scanned" has to be relative to the project being scanned.
#
# BUILD OUTPUT IS EXCLUDED, and that was learned by running this against a real project
# rather than reasoned out in advance. Pointed at `app/build/`, the first run reported
# eight genuine-looking failures from `intermediates/merged_jni_libs/release/...` -- a
# tree left behind by an assembleRelease from BEFORE the fix. Every finding was true of
# that stale artifact and false of the current source.
#
# Intermediates accumulate per variant and are never pruned, so a check reading them
# reports the worst state the directory has ever held. That is the "cries wolf" failure
# 050 was rewritten for: noise here would get the whole corpus ignored.
#
# So preflight covers what the REPOSITORY declares, where staleness is impossible. What
# a BUILD produced is verified by verify-apk-native-linkage.sh against a named artifact,
# where the caller is explicit about which one -- and by the packaging step itself, which
# should fail its own build rather than let the APK be assembled at all.
mapfile -t ABIDIRS < <(
    find "$ROOT" \
        \( -path "$ROOT/scripts/preflight/*" -o -path "$ROOT/.git/*" \
           -o -path "$ROOT/.appfactory/bin/*" -o -path "$ROOT/build" -o -path "$ROOT/*/build" \
           -o -path "$ROOT/plugins/*/runtime/scripts/preflight/*" \) -prune -o \
        -type d \( -name 'arm64-v8a' -o -name 'armeabi-v7a' -o -name 'x86' -o -name 'x86_64' \) -print \
        2>/dev/null | sort -u
)

CANDIDATES=()
for d in "${ABIDIRS[@]}"; do
    [ -n "$d" ] || continue
    [ -n "$(find "$d" -maxdepth 1 -name '*.so' -print -quit 2>/dev/null)" ] && CANDIDATES+=("$d")
done

if [ ${#CANDIDATES[@]} -eq 0 ]; then
    pass "$TITLE (no native libraries)"
    af_exit
fi

# Libraries the PLATFORM provides, so they are correctly absent from the APK. This is
# the NDK's stable ABI list. Anything outside it travels with the app or is not there
# at all -- libc++_shared.so is the everyday example, and is DELIBERATELY not listed:
# it must be bundled, and a missing one is exactly the bug this check exists to find.
PLATFORM='^lib(c|m|dl|log|android|z|EGL|GLESv1_CM|GLESv2|GLESv3|OpenSLES|OpenMAXAL|jnigraphics|vulkan|nativewindow|mediandk|camera2ndk|aaudio|neuralnetworks|stdc\+\+|nativehelper|sync)\.so$'

ok=1
checked=0
for dir in "${CANDIDATES[@]}"; do
    abi="$(basename "$dir")"
    rel="$(realpath --relative-to="$ROOT" "$dir" 2>/dev/null || printf '%s' "$dir")"
    while IFS= read -r so; do
        [ -n "$so" ] || continue
        checked=$((checked + 1))
        # `(NEEDED)` with the name in brackets is stable across readelf implementations;
        # matching on the bracketed value rather than column position survives the
        # differences in how each one pads its output.
        while IFS= read -r dep; do
            [ -n "$dep" ] || continue
            printf '%s\n' "$dep" | grep -Eq "$PLATFORM" && continue
            [ -f "$dir/$dep" ] && continue
            fail "$rel/$(basename "$so") needs $dep, which is neither provided by Android nor present in $abi/ -- dlopen will fail at runtime with: library '$dep' not found"
            ok=0
        done < <("$READELF" -d "$so" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
    done < <(find "$dir" -maxdepth 1 -name '*.so' -type f 2>/dev/null | sort)
done

[ "$ok" -eq 1 ] && pass "$TITLE ($checked libraries across ${#CANDIDATES[@]} ABI $([ ${#CANDIDATES[@]} -eq 1 ] && echo directory || echo directories))"
af_exit

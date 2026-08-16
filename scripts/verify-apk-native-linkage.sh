#!/usr/bin/env bash
# Verify that every native library IN A BUILT ARTIFACT can actually be loaded --
# specifically, that every DT_NEEDED entry resolves.
#
# THE COMPANION TO PREFLIGHT CHECK 190, and the split is deliberate. 190 reads what the
# REPOSITORY declares, where staleness is impossible. This reads what a BUILD produced,
# which is where the interesting defects live and where staleness is guaranteed: Gradle
# never prunes `app/build/intermediates/`, so a directory scan reports the worst state
# that tree has ever held. Pointing 190 at build output produced exactly that -- eight
# findings from a release build predating the fix, all true of the artifact and false of
# the source.
#
# The fix is not cleverness about mtimes. It is making the caller NAME the artifact.
#
# THE INCIDENT. Eight arm64 libraries declared NEEDED libomp.so and none of them shipped
# it. ggml links against OpenMP on arm64; libomp.so belongs to the NDK, not the build, and
# a workflow running cmake directly packages only what cmake wrote -- where building
# through AGP would have collected it. Build, unit tests, lint, R8 and eighteen preflight
# checks were all green. The app died at the first System.loadLibrary, on a device.
#
# usage: verify-apk-native-linkage.sh <artifact.apk | directory>
#
# Exits 1 on the first unresolved dependency found, 0 when every ABI is clean.

set -uo pipefail

TARGET="${1:-}"
if [ -z "$TARGET" ] || [ ! -e "$TARGET" ]; then
    printf 'usage: %s <artifact.apk | directory containing lib/<abi>/*.so>\n' "$(basename "$0")" >&2
    exit 2
fi

READELF=""
for c in readelf llvm-readelf eu-readelf; do
    command -v "$c" >/dev/null 2>&1 && { READELF="$c"; break; }
done
if [ -z "$READELF" ]; then
    printf 'FAIL no readelf on PATH -- install binutils. Refusing to report success on a check that did not run.\n' >&2
    exit 2
fi

WORK=""
cleanup() { [ -n "$WORK" ] && rm -rf "$WORK"; }
trap cleanup EXIT

case "$TARGET" in
    *.apk|*.aab|*.zip)
        command -v unzip >/dev/null 2>&1 || { printf 'FAIL unzip not available\n' >&2; exit 2; }
        WORK="$(mktemp -d)"
        unzip -q -o "$TARGET" 'lib/*' -d "$WORK" 2>/dev/null
        SCAN="$WORK"
        ;;
    *)
        SCAN="$TARGET"
        ;;
esac

# The NDK's stable ABI -- libraries the platform provides, so their absence from the
# artifact is correct. libc++_shared.so is DELIBERATELY not here: it must be bundled, and
# a missing one is precisely the defect this script exists to find.
PLATFORM='^lib(c|m|dl|log|android|z|EGL|GLESv1_CM|GLESv2|GLESv3|OpenSLES|OpenMAXAL|jnigraphics|vulkan|nativewindow|mediandk|camera2ndk|aaudio|neuralnetworks|stdc\+\+|nativehelper|sync)\.so$'

mapfile -t ABIDIRS < <(
    find "$SCAN" -type d \( -name 'arm64-v8a' -o -name 'armeabi-v7a' -o -name 'x86' -o -name 'x86_64' \) \
        2>/dev/null | sort -u
)

if [ ${#ABIDIRS[@]} -eq 0 ]; then
    printf 'FAIL %s contains no ABI directories -- nothing was verified, which is not the same as clean\n' "$TARGET" >&2
    exit 1
fi

rc=0
total=0
for dir in "${ABIDIRS[@]}"; do
    abi="$(basename "$dir")"
    unresolved=0
    count=0
    while IFS= read -r so; do
        [ -n "$so" ] || continue
        count=$((count + 1))
        while IFS= read -r dep; do
            [ -n "$dep" ] || continue
            printf '%s\n' "$dep" | grep -Eq "$PLATFORM" && continue
            # One ABI directory is the whole search path. A library built for another
            # machine sitting in a sibling directory is not a fallback.
            [ -f "$dir/$dep" ] && continue
            printf '  UNRESOLVED  %s/%s needs %s\n' "$abi" "$(basename "$so")" "$dep"
            unresolved=1
            rc=1
        done < <("$READELF" -d "$so" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
    done < <(find "$dir" -maxdepth 1 -name '*.so' -type f 2>/dev/null | sort)
    total=$((total + count))
    [ "$unresolved" -eq 0 ] && printf '  ok          %s: %d libraries, all dependencies present\n' "$abi" "$count"
done

if [ "$rc" -eq 0 ]; then
    printf '%s: %d native libraries across %d ABIs, every DT_NEEDED resolves\n' \
        "$(basename "$TARGET")" "$total" "${#ABIDIRS[@]}"
else
    printf '\n%s WILL FAIL AT dlopen. This is the defect that stays green through build,\n' "$(basename "$TARGET")" >&2
    printf 'unit tests, lint, R8 and preflight, and only appears on a device.\n' >&2
fi

# Says what it does NOT prove, because the honest limit matters: every dependency being
# PRESENT is not the same as the library LOADING. A missing symbol inside a library that
# is present will still get through this.
exit "$rc"

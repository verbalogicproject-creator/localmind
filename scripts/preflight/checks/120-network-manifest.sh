#!/usr/bin/env bash
# An app that makes HTTP calls declares INTERNET and activates its network config.
. "$(dirname "${BASH_SOURCE[0]}")/../lib.sh"

meta() {
    ID="120-network-manifest"
    TITLE="network permissions and config are wired"
    CATCHES="Two failures that reach a real device and are misread as server problems.

1. HTTP code with no android.permission.INTERNET. socket() fails with EPERM and the
   app reports 'Operation not permitted' -- which names the syscall, not the missing
   permission. It looks like the server is down. Loopback is still the network stack:
   even 127.0.0.1 needs it.

2. res/xml/network_security_config.xml present but never referenced from
   <application android:networkSecurityConfig>. The file is inert until wired, and
   cleartext is blocked by default from API 28, so every plaintext request fails
   while a file sitting in the tree suggests it was handled.

Both shipped in the same release. The first produced 'Operation not permitted' on
device; the second would have produced a connection failure immediately after fixing
it -- two round trips to a phone for one manifest edit.

Inverse of check 020: that one catches a build referencing a file that does not
exist, this one catches a file existing that nothing references."
    SCOPE="any"
}
meta
ROOT="$(af_root "${1:-}")"

MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
[ -f "$MANIFEST" ] || { pass "$TITLE (no manifest)"; af_exit; }
mapfile -t SRC < <(af_src_dirs "$ROOT")

ok=1

# 1. Does the app actually do networking?
if [ ${#SRC[@]} -gt 0 ] && grep -rqE 'io\.ktor|okhttp3|java\.net\.(URL|HttpURLConnection)|retrofit2|HttpClient\(' "${SRC[@]}" 2>/dev/null; then
    if ! grep -q 'android.permission.INTERNET' "$MANIFEST"; then
        fail "sources make HTTP calls but android.permission.INTERNET is not declared -- socket() will fail with EPERM ('Operation not permitted')"
        ok=0
    fi
fi

# 2. A network security config that nothing activates.
if [ -f "$ROOT/app/src/main/res/xml/network_security_config.xml" ]; then
    if ! grep -q 'android:networkSecurityConfig' "$MANIFEST"; then
        fail "res/xml/network_security_config.xml exists but <application> has no android:networkSecurityConfig -- the file is inert and cleartext stays blocked"
        ok=0
    fi
fi

# 3. The reverse: referenced but absent.
if grep -q 'android:networkSecurityConfig' "$MANIFEST"; then
    ref=$(grep -oE 'android:networkSecurityConfig="@xml/[A-Za-z0-9_]+"' "$MANIFEST" | sed 's/.*@xml\///; s/"//')
    if [ -n "$ref" ] && [ ! -f "$ROOT/app/src/main/res/xml/$ref.xml" ]; then
        fail "manifest references @xml/$ref but res/xml/$ref.xml does not exist"
        ok=0
    fi
fi

[ "$ok" -eq 1 ] && pass "$TITLE"
af_exit

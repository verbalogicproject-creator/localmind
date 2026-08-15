"""Read the signing certificate from an APK, with no Android SDK.

Modern AGP signs with APK Signature Scheme v2/v3 only -- there is no
META-INF/*.RSA to read, so the PKCS#7/JAR-signing approach finds nothing. The
certificate lives in the APK Signing Block, between the ZIP entries and the
central directory.
"""
import hashlib, struct, sys

V2_ID, V3_ID, V31_ID = 0x7109871a, 0xf05368c0, 0x1b93ad61
MAGIC = b'APK Sig Block 42'


def _eocd_cd_offset(data):
    # End of Central Directory: signature PK\x05\x06, within the last 64KiB.
    start = max(0, len(data) - 65536 - 22)
    idx = data.rfind(b'PK\x05\x06', start)
    if idx < 0:
        raise SystemExit("no EOCD record: not a zip file")
    return struct.unpack_from('<I', data, idx + 16)[0]


def _signing_block(data):
    cd = _eocd_cd_offset(data)
    if data[cd - 16:cd] != MAGIC:
        raise SystemExit("no APK Signing Block (v1-only or unsigned)")
    size = struct.unpack_from('<Q', data, cd - 24)[0]
    start = cd - size - 8
    return data[start + 8:cd - 24]


def _pairs(block):
    off = 0
    while off + 12 <= len(block):
        length = struct.unpack_from('<Q', block, off)[0]
        pid = struct.unpack_from('<I', block, off + 8)[0]
        yield pid, block[off + 12:off + 8 + length]
        off += 8 + length


def _u32(buf, off):
    return struct.unpack_from('<I', buf, off)[0], off + 4


def first_cert_der(value):
    # signers <len> -> signer <len> -> signed data <len> -> digests <len> ->
    # certificates <len> -> certificate <len> -> X.509 DER
    _, o = _u32(value, 0)          # signers sequence length
    _, o = _u32(value, o)          # first signer length
    _, o = _u32(value, o)          # signed data length
    dlen, o = _u32(value, o)       # digests sequence length
    o += dlen                      # skip digests
    _, o = _u32(value, o)          # certificates sequence length
    clen, o = _u32(value, o)       # first certificate length
    return value[o:o + clen]


def cert_sha256(path):
    with open(path, 'rb') as f:
        data = f.read()
    block = _signing_block(data)
    found = dict(_pairs(block))
    # Prefer v3 when present: it is the current signer after any key rotation.
    for pid in (V31_ID, V3_ID, V2_ID):
        if pid in found:
            return hashlib.sha256(first_cert_der(found[pid])).hexdigest(), hex(pid)
    raise SystemExit(f"no v2/v3 signature block; found ids: {[hex(k) for k in found]}")


if __name__ == '__main__':
    digest, scheme = cert_sha256(sys.argv[1])
    print(f"scheme={scheme}")
    print(digest)

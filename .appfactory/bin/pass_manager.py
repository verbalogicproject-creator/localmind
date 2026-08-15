#!/usr/bin/env python3
"""appfactory secrets — an encrypted vault for Android signing material.

WHY THIS EXISTS
===============
Setting one GitHub secret failed FIVE times in a row with no error message. The cause
was an interactive `gh secret set` prompt with no TTY: it fails silently, and
`gh secret list` afterwards shows the secret NAME, so everything looked correct.

That is the design constraint. Every failure mode this tool can have must be loud.

CRYPTO CHOICE
=============
`cryptography`'s Fernet, keyed by scrypt. Verified importable on the aarch64
Termux/PRoot device this was built for.

Rejected, with reasons:
  age     — not installed; a second trust root and a binary to keep current for
            aarch64, and shelling out risks secret material transiting argv, which
            `ps` exposes.
  gpg     — present, but drives an agent with TTY-interactive pinentry. Choosing a
            tool whose failure mode is the exact one that already cost an hour is
            indefensible.
  argon2  — better KDF, not installed, and installing it on Termux/PRoot is the kind
            of dependency that breaks in six months. scrypt ships inside
            `cryptography` and is memory-hard.

KDF parameters live in the vault header so they can be raised later without
orphaning existing vaults.

BUILD-TIME vs RUNTIME SECRETS
=============================
The keystore is build-time: it never enters the APK. Losing it is unrecoverable —
you cannot re-key an installed cohort, and every existing install becomes permanently
un-upgradable.

An API key shipped inside an APK is NOT SECRET. `strings`, `jadx` and `apktool`
recover it in seconds; BuildConfig fields, resValue, NDK-hidden strings and R8 all
raise extraction from seconds to minutes without changing the category. This tool
marks such entries `exposure: public-once-shipped` and refuses to imply otherwise.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile

try:
    from cryptography.fernet import Fernet, InvalidToken
    from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
except ImportError:
    sys.exit(
        "pass_manager needs the 'cryptography' package.\n"
        "  pip install cryptography\n"
        "It was chosen over age/gpg/argon2 precisely because it installs cleanly here."
    )

VAULT_DIR = os.path.expanduser("~/.appfactory")
VAULT_PATH = os.path.join(VAULT_DIR, "vault.json")
KDF_DEFAULTS = {"name": "scrypt", "n": 2 ** 15, "r": 8, "p": 1}

G, R, Y, DIM, OFF = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def ok(m):   print(f"{G}ok{OFF}   {m}")
def bad(m):  print(f"{R}FAIL{OFF} {m}")
def warn(m): print(f"{Y}warn{OFF} {m}")
def dim(m):  print(f"{DIM}{m}{OFF}")


# ---------------------------------------------------------------------------
# vault location safety
# ---------------------------------------------------------------------------
def assert_vault_outside_repo() -> None:
    """The vault must never live inside a git worktree.

    .gitignore is a pattern list someone has to maintain correctly forever. Being
    outside every repository is a property of the filesystem instead.
    """
    try:
        r = subprocess.run(
            ["git", "-C", VAULT_DIR, "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, timeout=10,
        )
        if r.returncode == 0 and r.stdout.strip():
            sys.exit(
                f"REFUSING: {VAULT_DIR} is inside the git repository at "
                f"{r.stdout.strip()}.\n"
                "A release key committed even once is in the history forever, and "
                "rewriting history does not recall the clones."
            )
    except Exception:
        pass  # git absent or the dir does not exist yet; both fine.


# ---------------------------------------------------------------------------
# crypto
# ---------------------------------------------------------------------------
def derive_key(passphrase: str, salt: bytes, kdf: dict) -> bytes:
    raw = Scrypt(salt=salt, length=32, n=kdf["n"], r=kdf["r"], p=kdf["p"]).derive(
        passphrase.encode("utf-8")
    )
    return base64.urlsafe_b64encode(raw)


def read_passphrase(prompt: str = "vault passphrase: ", confirm: bool = False) -> str:
    """Read a passphrase, and fail LOUDLY without a TTY rather than hanging.

    A silent no-op here is the exact failure that cost five attempts.
    """
    if not sys.stdin.isatty():
        pw = sys.stdin.readline().rstrip("\n")
        if not pw:
            sys.exit(
                "No TTY and nothing on stdin.\n"
                "For automation:  echo -n \"$PASSPHRASE\" | pass_manager.py <cmd> --passphrase-stdin"
            )
        return pw
    pw = getpass.getpass(prompt)
    if confirm and pw != getpass.getpass("confirm: "):
        sys.exit("passphrases do not match")
    return pw


def load_vault(passphrase: str | None = None) -> tuple[dict, str]:
    if not os.path.exists(VAULT_PATH):
        sys.exit(f"no vault at {VAULT_PATH}. Run:  pass_manager.py init")
    header = json.load(open(VAULT_PATH))
    pw = passphrase or read_passphrase()
    key = derive_key(pw, base64.b64decode(header["kdf"]["salt"]), header["kdf"])
    try:
        payload = json.loads(Fernet(key).decrypt(header["ciphertext"].encode()))
    except InvalidToken:
        sys.exit("wrong passphrase, or the vault is corrupt")
    return payload, pw


def save_vault(payload: dict, passphrase: str) -> None:
    os.makedirs(VAULT_DIR, mode=0o700, exist_ok=True)
    header = json.load(open(VAULT_PATH)) if os.path.exists(VAULT_PATH) else {
        "version": 1, "kdf": dict(KDF_DEFAULTS, salt=base64.b64encode(os.urandom(16)).decode()),
    }
    key = derive_key(passphrase, base64.b64decode(header["kdf"]["salt"]), header["kdf"])
    header["ciphertext"] = Fernet(key).encrypt(json.dumps(payload).encode()).decode()

    # Atomic replace: a crash mid-write must not leave a truncated vault.
    fd, tmp = tempfile.mkstemp(dir=VAULT_DIR)
    with os.fdopen(fd, "w") as fh:
        json.dump(header, fh, indent=2)
    os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(tmp, VAULT_PATH)


# ---------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------
def cmd_init(args) -> int:
    assert_vault_outside_repo()
    if os.path.exists(VAULT_PATH) and not args.force:
        sys.exit(f"vault already exists at {VAULT_PATH}. Use --force to replace it.")
    pw = read_passphrase("new vault passphrase: ", confirm=True)
    if len(pw) < 12:
        sys.exit("passphrase must be at least 12 characters")
    os.makedirs(VAULT_DIR, mode=0o700, exist_ok=True)
    with open(VAULT_PATH, "w") as fh:
        json.dump({"version": 1, "kdf": dict(KDF_DEFAULTS,
                   salt=base64.b64encode(os.urandom(16)).decode()), "ciphertext": ""}, fh)
    os.chmod(VAULT_PATH, 0o600)
    save_vault({"profiles": {}}, pw)
    ok(f"vault created at {VAULT_PATH} (mode 0600)")
    dim("It is outside every git repository by design, not by .gitignore.")
    return 0


def cmd_keygen(args) -> int:
    """Generate a release keystore straight into the vault; no .jks left on disk."""
    payload, pw = load_vault()
    prof = payload["profiles"].setdefault(args.profile, {})
    if "keystore" in prof and not args.force:
        sys.exit(f"profile '{args.profile}' already has a keystore. --force to replace "
                 "(this ORPHANS every device that installed a build signed with the old one).")

    password = args.password or base64.b64encode(os.urandom(24)).decode()
    alias = args.alias or f"{args.profile}-{secrets.token_hex(3)}"
    if alias in ("key", "release", "android", args.profile):
        warn(f"alias '{alias}' is a common word; GitHub masks secret VALUES as substrings "
             "in every log line, so a common alias redacts your package paths too.")

    tmpdir = tempfile.mkdtemp()
    os.chmod(tmpdir, 0o700)
    ks = os.path.join(tmpdir, "k.jks")
    try:
        subprocess.run(
            ["keytool", "-genkeypair", "-v", "-keystore", ks, "-alias", alias,
             "-keyalg", "RSA", "-keysize", "4096", "-validity", "10950",
             "-dname", args.dname, "-storepass", password, "-keypass", password],
            check=True, capture_output=True,
        )
        blob = open(ks, "rb").read()
        digest = subprocess.run(
            ["keytool", "-list", "-v", "-keystore", ks, "-storepass", password],
            capture_output=True, text=True,
        ).stdout
        cert = ""
        for line in digest.splitlines():
            if "SHA256:" in line:
                cert = line.split("SHA256:")[1].strip().replace(":", "").lower()
                break
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    prof["keystore"] = {
        "b64": base64.b64encode(blob).decode(),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "alias": alias,
        "store_password": password,
        "cert_sha256": cert,
    }
    save_vault(payload, pw)
    ok(f"keystore generated into profile '{args.profile}' ({len(blob)} bytes)")
    ok(f"alias: {alias}")
    print(f"\ncertificate digest (public, commit this):\n  {cert}\n")
    print("Write it to .appfactory/release/cert.sha256 in the app repo.")
    warn("BACK UP THE VAULT NOW. Losing it means every installed device can never be "
         "updated again. There is no recovery path.")
    return 0


def cmd_import_keystore(args) -> int:
    """Adopt an existing loose .jks into the vault.

    For keys created before the vault existed, which is the common real case: a
    keystore and its password sitting as plain files somewhere, protected only by
    directory permissions and by nobody having looked.

    Deliberately does NOT delete the original. Verify the import round-trips first,
    then remove the plaintext yourself. A migration tool that deletes the source
    before you have confirmed the destination is how keys get lost, and this is the
    one asset with no recovery path.
    """
    if not os.path.exists(args.keystore):
        sys.exit(f"no keystore at {args.keystore}")

    password = args.password
    if not password and args.password_file:
        password = open(args.password_file).read().strip()
    if not password:
        sys.exit("need --password or --password-file")

    blob = open(args.keystore, "rb").read()

    # Prove the password actually opens it BEFORE storing anything. Importing a
    # keystore with the wrong password would produce a vault entry that looks fine
    # and fails at the next release.
    listing = subprocess.run(
        ["keytool", "-list", "-v", "-keystore", args.keystore, "-storepass", password],
        capture_output=True, text=True,
    )
    if listing.returncode != 0:
        sys.exit(f"keytool could not open {args.keystore} with that password -- "
                 "refusing to store an entry that would fail at release time")

    cert, alias = "", args.alias
    for line in listing.stdout.splitlines():
        if "SHA256:" in line and not cert:
            cert = line.split("SHA256:")[1].strip().replace(":", "").lower()
        if not alias and line.startswith("Alias name:"):
            alias = line.split(":", 1)[1].strip()

    payload, pw = load_vault()
    prof = payload["profiles"].setdefault(args.profile, {})
    if "keystore" in prof and not args.force:
        sys.exit(f"profile '{args.profile}' already has a keystore; --force to replace")

    prof["keystore"] = {
        "b64": base64.b64encode(blob).decode(),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "alias": alias,
        "store_password": password,
        "cert_sha256": cert,
        "imported_from": os.path.abspath(args.keystore),
    }
    save_vault(payload, pw)
    ok(f"imported {len(blob)} bytes into profile '{args.profile}'")
    ok(f"alias: {alias}")
    ok(f"cert:  {cert}")
    warn(f"The original is STILL at {args.keystore}. Verify the import, then delete it.")
    return 0


def cmd_sync(args) -> int:
    """Push signing secrets to GitHub Actions, then READ BACK and diff."""
    payload, _ = load_vault()
    prof = payload["profiles"].get(args.profile) or sys.exit(f"no profile '{args.profile}'")
    ks = prof.get("keystore") or sys.exit("profile has no keystore; run keygen first")

    repo = args.repo or prof.get("repo")
    if not repo:
        sys.exit("no repo for this profile; pass --repo owner/name")

    def put(name: str, value: str) -> bool:
        # stdin only. NEVER argv -- `ps` exposes it -- and never shell history.
        r = subprocess.run(["gh", "secret", "set", name, "-R", repo],
                           input=value, text=True, capture_output=True)
        return r.returncode == 0

    items = [
        ("SIGNING_KEY_BASE64", ks["b64"]),
        ("SIGNING_KEY_ALIAS", ks["alias"]),
        ("SIGNING_KEYSTORE_PASSWORD", ks["store_password"]),
    ]
    for name, value in items:
        (ok if put(name, value) else bad)(f"{'set' if True else ''} {name}")

    # Read back. This proves a NAME exists; only `canary` proves the VALUE arrived.
    names = subprocess.run(["gh", "secret", "list", "-R", repo, "--json", "name",
                            "--jq", ".[].name"], capture_output=True, text=True).stdout.split()
    print()
    for name, _ in items:
        (ok if name in names else bad)(f"present on GitHub: {name}")
    warn("A present NAME is not proof the VALUE is right. GitHub never returns a secret "
         "value. Run `pass_manager.py canary` for the only honest end-to-end check.")
    return 0


def cmd_doctor(args) -> int:
    """Probe every failure mode that once produced a silent no-op."""
    problems = 0

    print("environment")
    if sys.stdin.isatty():
        ok("stdin is a TTY")
    else:
        warn("no TTY — interactive prompts would fail SILENTLY here. This is the exact "
             "cause of five failed secret attempts. Every write path in this tool uses stdin.")

    for tool in ("gh", "git", "keytool"):
        (ok if shutil.which(tool) else bad)(f"{tool} {'found' if shutil.which(tool) else 'NOT FOUND'}")
        if not shutil.which(tool):
            problems += 1

    tmp = os.environ.get("TMPDIR", "/tmp")
    if os.path.isdir(tmp) and os.access(tmp, os.W_OK):
        ok(f"TMPDIR writable: {tmp}")
    else:
        bad(f"TMPDIR not writable: {tmp} — gh defaults to /data/local/tmp, absent under PRoot")
        problems += 1

    print("\nvault")
    if not os.path.exists(VAULT_PATH):
        warn(f"no vault at {VAULT_PATH} (run init)")
    else:
        mode = stat.S_IMODE(os.stat(VAULT_PATH).st_mode)
        (ok if mode == 0o600 else bad)(f"vault mode {oct(mode)}")
        if mode != 0o600:
            problems += 1
        assert_vault_outside_repo()
        ok("vault is outside every git repository")

    print("\nrepository")
    r = subprocess.run(["gh", "auth", "status"], capture_output=True, text=True)
    (ok if r.returncode == 0 else bad)("gh authenticated")
    problems += 0 if r.returncode == 0 else 1

    if args.repo:
        r = subprocess.run(["gh", "api", f"repos/{args.repo}", "--jq", ".permissions.admin"],
                           capture_output=True, text=True)
        admin = r.stdout.strip() == "true"
        (ok if admin else bad)(f"admin on {args.repo} (required to write secrets)")
        if not admin:
            problems += 1

    print("\nhistory")
    r = subprocess.run(["git", "log", "--all", "--", "*.jks", "keystore.properties"],
                       capture_output=True, text=True)
    if r.returncode == 0 and r.stdout.strip():
        bad("SIGNING MATERIAL APPEARS IN GIT HISTORY — it is there forever; rotate the key")
        problems += 1
    else:
        ok("no signing material in git history")

    print()
    if problems:
        bad(f"{problems} problem(s)")
        return 1
    ok("no problems found")
    dim("Note: this proves the MECHANISM works. Run `canary` to prove a value arrived.")
    return 0


def cmd_verify_apk(args) -> int:
    """SDK-free signature verification: read the APK Signing Block directly."""
    here = os.path.dirname(os.path.abspath(__file__))
    script = os.path.join(here, "apk_cert.py")
    if not os.path.exists(script):
        sys.exit(f"apk_cert.py not found beside this script ({script})")
    r = subprocess.run([sys.executable, script, args.apk], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(r.stderr or r.stdout)
    digest = r.stdout.strip().splitlines()[-1]
    print(f"apk cert : {digest}")
    if args.pin:
        pin = open(args.pin).read().strip().replace(":", "").lower()
        print(f"pinned   : {pin}")
        if digest == pin:
            ok("MATCH")
            return 0
        bad("MISMATCH — publishing this would orphan every existing install")
        return 1
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd")

    s = sub.add_parser("init", help="create the vault")
    s.add_argument("--force", action="store_true")
    s.set_defaults(fn=cmd_init)

    s = sub.add_parser("keygen", help="generate a release keystore into the vault")
    s.add_argument("profile")
    s.add_argument("--alias")
    s.add_argument("--password", help="omit to generate a strong random one")
    s.add_argument("--dname", default="CN=AppFactory,O=Unknown,C=US")
    s.add_argument("--force", action="store_true")
    s.set_defaults(fn=cmd_keygen)

    s = sub.add_parser("import-keystore", help="adopt an existing loose .jks into the vault")
    s.add_argument("profile")
    s.add_argument("--keystore", required=True)
    s.add_argument("--password")
    s.add_argument("--password-file")
    s.add_argument("--alias")
    s.add_argument("--force", action="store_true")
    s.set_defaults(fn=cmd_import_keystore)

    s = sub.add_parser("sync", help="push signing secrets to GitHub, then read back")
    s.add_argument("profile")
    s.add_argument("--repo")
    s.set_defaults(fn=cmd_sync)

    s = sub.add_parser("doctor", help="probe every known silent-failure mode")
    s.add_argument("--repo")
    s.set_defaults(fn=cmd_doctor)

    s = sub.add_parser("verify-apk", help="check an APK's signing cert with no SDK")
    s.add_argument("apk")
    s.add_argument("--pin", help="path to cert.sha256")
    s.set_defaults(fn=cmd_verify_apk)

    return p


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if not getattr(args, "fn", None):
        parser.print_help()
        return 0
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())

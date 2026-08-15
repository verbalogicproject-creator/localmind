#!/usr/bin/env python3
"""Generate an Android walking skeleton from the appfactory templates.

The mechanical half of bootstrap. Deterministic and testable on its own, so the
skill above it only has to handle decisions — and so `bootstrap reproduces the
conformance repo` is a claim that can actually be diffed.

WHAT A WALKING SKELETON IS FOR
------------------------------
An app that does nothing except launch, be signed, install, and display its own
versionName, versionCode and git SHA. It goes all the way through the pipeline
BEFORE any feature code exists.

That ordering is the point. Afterwards every failure is attributable to app code
rather than to the pipeline, which halves the diagnostic search space for the rest
of the project's life. Building features first defers the first end-to-end signal
until after the most expensive stage.

    scaffold.py <target-dir> --application-id com.example.app --app-name "My App"
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RUNTIME = os.path.dirname(HERE)
TEMPLATES = os.path.join(RUNTIME, "templates")

APPLICATION_ID_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")

G, R, Y, DIM, OFF = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def ok(m): print(f"{G}ok{OFF}   {m}")
def die(m): sys.exit(f"{R}ERROR{OFF} {m}")
def note(m): print(f"{DIM}     {m}{OFF}")


def substitutions(application_id: str, app_name: str) -> dict[str, str]:
    # The class name derives from the app name, not the package, so it stays
    # readable when the package is a reverse domain.
    app_class = "".join(w.capitalize() for w in re.split(r"[^A-Za-z0-9]+", app_name) if w) or "App"
    slug = re.sub(r"[^a-z0-9]+", "-", app_name.lower()).strip("-") or "app"
    return {
        "com.verbalogix.assistant": application_id,
        "Localmind": app_class,
        "Localmind": app_name,
        "LOCALMIND": app_name.upper(),
        "localmind": slug,
        "Localmind": app_class,
        "walking skeleton": "walking skeleton",
    }


def render(text: str, subs: dict[str, str]) -> str:
    for k, v in subs.items():
        text = text.replace(k, v)
    return text


def copy_rendered(src: str, dst: str, subs: dict[str, str]) -> None:
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    try:
        body = open(src, encoding="utf-8").read()
    except UnicodeDecodeError:
        shutil.copy2(src, dst)  # binary (the gradle wrapper jar)
        return
    open(dst, "w", encoding="utf-8").write(render(body, subs))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("target")
    ap.add_argument("--application-id", required=True)
    ap.add_argument("--app-name", required=True)
    ap.add_argument("--min-sdk", type=int, default=28)
    ap.add_argument("--target-sdk", type=int, default=34)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    app_id = args.application_id
    # applicationId is one of exactly three things that can never change after the
    # first install. Validate it here rather than discover it at upload time.
    if not APPLICATION_ID_RE.match(app_id):
        die(f"applicationId '{app_id}' is not a valid Android package name.\n"
            "       Needs at least two lowercase segments, e.g. com.example.myapp.\n"
            "       It can NEVER be changed after the first user installs.")
    if args.min_sdk < 26:
        die(f"minSdk {args.min_sdk} < 26: adaptive icons need 26, and the templates "
            "ship no PNG fallbacks.")

    target = os.path.abspath(args.target)
    if os.path.exists(target) and os.listdir(target) and not args.force:
        die(f"{target} exists and is not empty. Use --force to scaffold into it anyway.")

    subs = substitutions(app_id, args.app_name)
    subs["28"] = str(args.min_sdk)
    subs["34"] = str(args.target_sdk)
    pkg_path = app_id.replace(".", "/")

    print(f"scaffolding {args.app_name}")
    note(f"applicationId  {app_id}   (IRREVERSIBLE)")
    note(f"class          {subs['Localmind']}")
    note(f"minSdk/target  {args.min_sdk}/{args.target_sdk}")
    print()

    # --- gradle ------------------------------------------------------------
    gradle = os.path.join(TEMPLATES, "gradle")
    mapping = {
        "app.build.gradle.kts": "app/build.gradle.kts",
        "root.build.gradle.kts": "build.gradle.kts",
        "settings.gradle.kts": "settings.gradle.kts",
        "gradle.properties": "gradle.properties",
        "libs.versions.toml": "gradle/libs.versions.toml",
        "proguard-rules.pro": "app/proguard-rules.pro",
        "proguard-test-rules.pro": "app/proguard-test-rules.pro",
        "gitignore": ".gitignore",
    }
    for src_name, dst_rel in mapping.items():
        src = os.path.join(gradle, src_name)
        if os.path.exists(src):
            copy_rendered(src, os.path.join(target, dst_rel), subs)
    ok(f"gradle files ({len(mapping)})")

    # --- app sources, into the real package directory ----------------------
    app_tpl = os.path.join(TEMPLATES, "app")
    count = 0
    for root, _, files in os.walk(app_tpl):
        for f in files:
            src = os.path.join(root, f)
            rel = os.path.relpath(src, app_tpl)
            # java/Foo.kt -> java/<pkg path>/Foo.kt, with ui/ and theme/ nesting
            if "/java/" in f"/{rel}":
                head, name = rel.rsplit("/java/", 1)
                sub_dir = {"HomeScreen.kt": "ui", "Theme.kt": "ui/theme"}.get(name, "")
                rel = os.path.join(head, "java", pkg_path, sub_dir, name)
            copy_rendered(src, os.path.join(target, "app", rel), subs)
            count += 1
    ok(f"app sources ({count})")

    # --- vendored runtime --------------------------------------------------
    #
    # scripts/ must be RENDERED, not copied. emulator-verify.sh contains
    # com.verbalogix.assistant, and copying it verbatim produced
    #     Error: Activity class {com.verbalogix.assistant/...MainActivity} does not exist
    # on the emulator — the launch smoke caught it honestly, but only after a
    # 10-minute run, and only because that rung exists at all.
    #
    # The fixture trees under scripts/preflight/fixtures/ are copied verbatim on
    # purpose: they are deliberately-broken sample projects, and rendering them
    # would corrupt the very bugs they encode.
    for sub_dir, dst in (("scripts", "scripts"), ("bin", ".appfactory/bin")):
        src_root = os.path.join(RUNTIME, sub_dir)
        if not os.path.isdir(src_root):
            continue
        for root, _, files in os.walk(src_root):
            for f in files:
                s = os.path.join(root, f)
                rel = os.path.relpath(s, src_root)
                d = os.path.join(target, dst, rel)
                if "preflight/fixtures/" in rel.replace(os.sep, "/"):
                    os.makedirs(os.path.dirname(d), exist_ok=True)
                    shutil.copy2(s, d)
                else:
                    copy_rendered(s, d, subs)
                if f.endswith((".sh", ".py")):
                    os.chmod(d, 0o755)
    for wf in os.listdir(os.path.join(RUNTIME, "workflows")):
        copy_rendered(os.path.join(RUNTIME, "workflows", wf),
                      os.path.join(target, ".github/workflows", wf), subs)
    ok("runtime vendored (preflight + its fixtures, workflows, bin)")

    os.makedirs(os.path.join(target, ".appfactory/release"), exist_ok=True)
    ok(".appfactory/ created")

    print()
    print("NEXT, in order — each step is cheap and the order is load-bearing:")
    note("1. generate the signing key:  pass_manager.py keygen <profile>")
    note("2. write its cert digest to  .appfactory/release/cert.sha256")
    note("3. push and PROVE the secrets (a present name is not a correct value)")
    note("4. push, watch CI, then tag v0.0.1 and install it on the phone")
    print()
    note("Do not add features until the skeleton has launched on a real device.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

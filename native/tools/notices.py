#!/usr/bin/env python3
"""Regenerates the crate table in THIRD-PARTY-NOTICES.md.

1. Asks cargo which crates end up in the release build of `sable_rapier`
   (`cargo tree -e normal`, offline), so dev-dependencies (criterion & friends) are excluded.
2. Reads the matching license identifiers from the vendored manifests
   (`.native-src/.../vendor/*/Cargo.toml`, produced by native/tools/vendor.py).
3. Rewrites the block between the GENERATED markers in THIRD-PARTY-NOTICES.md.

Run after bumping the native dependency set:

    python native/tools/notices.py

If cargo or the vendor directory is unavailable the script falls back to listing the whole
vendored set and says so.
"""

import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NOTICES = os.path.join(ROOT, "THIRD-PARTY-NOTICES.md")
RUST_DIR = os.path.join(
    ROOT, ".native-src", "sable", "sable_rapier", "src", "main", "rust"
)
VENDOR = os.path.join(RUST_DIR, "vendor")

BEGIN = "<!-- BEGIN GENERATED: do not edit by hand, regenerate with native/tools/notices.py -->"
END = "<!-- END GENERATED -->"

NAME_RE = re.compile(r'^name\s*=\s*"([^"]+)"', re.M)
VERSION_RE = re.compile(r'^version\s*=\s*"([^"]+)"', re.M)
LICENSE_RE = re.compile(r'^license\s*=\s*"([^"]+)"', re.M)
TREE_RE = re.compile(r"^(\S+) v([^\s]+)(?:\s+\((?:proc-macro|\*)\))?$")


def vendor_licenses(vendor):
    """(name, version) -> license identifier, from the vendored manifests."""
    licences = {}
    for entry in sorted(os.listdir(vendor)):
        manifest = os.path.join(vendor, entry, "Cargo.toml")
        if not os.path.isfile(manifest):
            continue
        text = io.open(manifest, encoding="utf-8", errors="replace").read()
        name = NAME_RE.search(text)
        version = VERSION_RE.search(text)
        license_id = LICENSE_RE.search(text)
        if name and version:
            licences[(name.group(1), version.group(1))] = (
                license_id.group(1) if license_id else "UNKNOWN"
            )
    return licences


def cargo_tree(licences):
    """[(name, version)] linked into the release build, or None if cargo is unavailable."""
    env = dict(os.environ)
    env.setdefault("CARGO_HOME", os.path.join(ROOT, ".cargo-home"))
    env["CARGO_NET_OFFLINE"] = "true"
    try:
        out = subprocess.run(
            ["cargo", "tree", "--offline", "-p", "sable_rapier", "-e", "normal", "--prefix", "none"],
            cwd=RUST_DIR,
            env=env,
            capture_output=True,
            text=True,
            timeout=180,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        print("cargo tree failed (%s); falling back to the whole vendored set" % exc)
        return None

    if out.returncode != 0:
        print("cargo tree failed; falling back to the whole vendored set")
        print(out.stderr.strip()[:500])
        return None

    crates = set()
    for line in out.stdout.splitlines():
        match = TREE_RE.match(line.strip())
        if match:
            crates.add((match.group(1), match.group(2)))
    return sorted(crates)


def main():
    if not os.path.isdir(VENDOR):
        sys.exit(
            "vendor directory not found: %s\n"
            "Populate it with native/tools/vendor.py first." % VENDOR
        )

    licences = vendor_licenses(VENDOR)
    linked = cargo_tree(licences)

    if linked is None:
        crates = sorted(licences.items())
        note = (
            "> Generated from the full vendored set (`cargo` was unavailable), which also "
            "contains test/benchmark-only crates.\n"
        )
    else:
        crates = [(key, licences.get(key, "UNKNOWN")) for key in linked]
        note = (
            "> Crates linked into the release build of `sable_rapier` (%d entries), "
            "excluding dev-dependencies such as `criterion`.\n" % len(crates)
        )

    rows = ["| `%s` | %s | %s |" % (n, v, lic) for (n, v), lic in crates]

    text = io.open(NOTICES, encoding="utf-8", newline="").read()
    start = text.index(BEGIN)
    end = text.index(END)

    generated = (
        BEGIN
        + "\n\n"
        + note
        + "\n### 3.1 Crate list\n\n| crate | version | license |\n|---|---|---|\n"
        + "\n".join(rows)
        + "\n\n"
        + END
    )

    io.open(
        NOTICES, "w", encoding="utf-8", newline=""
    ).write(text[:start] + generated + text[end + len(END):])
    print("wrote %d crates into %s" % (len(crates), os.path.basename(NOTICES)))


if __name__ == "__main__":
    main()

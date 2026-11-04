#!/usr/bin/env python3
"""Populate a cargo `directory` source (vendor dir) from a Cargo.lock, without cargo's own network.

Every crate is fetched from static.crates.io over Python's own TLS stack. This exists because on
some Windows setups schannel-based TLS (curl / git / cargo / rustup) fails with
SEC_E_NO_CREDENTIALS, while Python's OpenSSL + the system trust store works fine.

Usage:
    python vendor.py <Cargo.lock> <vendor-dir> [jobs]

Then point cargo at it (see `.cargo/config.toml` in the native workspace):

    [source.crates-io]
    replace-with = "vendored-sources"

    [source.vendored-sources]
    directory = "vendor"

Each crate is verified against the `checksum` recorded in Cargo.lock before it is extracted, and a
`.cargo-checksum.json` is written the same way `cargo vendor` does.
"""

from __future__ import annotations

import concurrent.futures
import hashlib
import io
import re
import sys
import tarfile
import urllib.request
from pathlib import Path

CRATE_URL = "https://static.crates.io/crates/{name}/{name}-{version}.crate"

PACKAGE_RE = re.compile(r"^\[\[package\]\]$", re.M)
NAME_RE = re.compile(r'^name = "([^"]+)"', re.M)
VERSION_RE = re.compile(r'^version = "([^"]+)"', re.M)
SOURCE_RE = re.compile(r'^source = "([^"]+)"', re.M)
CHECKSUM_RE = re.compile(r'^checksum = "([0-9a-f]+)"', re.M)


def parse_lock(text: str) -> list[tuple[str, str, str]]:
    """[(name, version, checksum)] for every registry package in the lock file."""
    crates: list[tuple[str, str, str]] = []

    for block in PACKAGE_RE.split(text)[1:]:
        source = SOURCE_RE.search(block)
        if source is None or not source.group(1).startswith("registry+"):
            continue

        name = NAME_RE.search(block)
        version = VERSION_RE.search(block)
        checksum = CHECKSUM_RE.search(block)

        if name is None or version is None:
            continue

        if checksum is None:
            print(f"!! no checksum for {name.group(1)} {version.group(1)}, skipping", file=sys.stderr)
            continue

        crates.append((name.group(1), version.group(1), checksum.group(1)))

    return crates


def extract_crate(archive: bytes, name: str, version: str, target: Path) -> None:
    """Unpack `<name>-<version>/` from the .crate tarball into `target`."""
    prefix = f"{name}-{version}/"
    target.mkdir(parents=True, exist_ok=True)

    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        for member in tar.getmembers():
            if not member.name.startswith(prefix):
                raise RuntimeError(f"unexpected archive layout for {name} {version}: {member.name}")

            relative = member.name[len(prefix):]
            if not relative:
                continue

            parts = Path(relative).parts
            if ".." in parts or member.name.startswith("/"):
                raise RuntimeError(f"refusing suspicious path in {name} {version}: {member.name}")

            destination = target.joinpath(*parts)

            if member.isdir():
                destination.mkdir(parents=True, exist_ok=True)
                continue

            if not member.isfile():
                # Symlinks are not expected in published crates; skip rather than follow.
                print(f"!! skipping non-regular file in {name} {version}: {relative}", file=sys.stderr)
                continue

            source = tar.extractfile(member)
            if source is None:
                continue

            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source.read())


def write_checksums(target: Path, package_digest: str) -> None:
    """Write `.cargo-checksum.json` in the format `cargo vendor` produces."""
    files = {}
    for path in sorted(target.rglob("*")):
        if path.is_file() and path.name != ".cargo-checksum.json":
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            files[path.relative_to(target).as_posix()] = digest

    (target / ".cargo-checksum.json").write_text(
        __import__("json").dumps({"files": files, "package": package_digest}),
        encoding="utf-8",
    )


def fetch_one(crate: tuple[str, str, str], vendor: Path) -> str:
    name, version, expected = crate
    target = vendor / f"{name}-{version}"

    marker = target / ".cargo-checksum.json"
    if marker.exists():
        return f"= {name} {version} (cached)"

    with urllib.request.urlopen(CRATE_URL.format(name=name, version=version), timeout=60) as response:
        archive = response.read()

    digest = hashlib.sha256(archive).hexdigest()

    if digest != expected:
        raise RuntimeError(f"checksum mismatch for {name} {version}: {digest} != {expected}")

    extract_crate(archive, name, version, target)
    write_checksums(target, digest)
    return f"+ {name} {version}"


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print("usage: python vendor.py <Cargo.lock> <vendor-dir> [jobs]", file=sys.stderr)
        return 2

    lock = Path(argv[1])
    vendor = Path(argv[2])
    jobs = int(argv[3]) if len(argv) > 3 else 8

    crates = parse_lock(lock.read_text(encoding="utf-8"))
    print(f"{len(crates)} registry packages to vendor into {vendor}")

    vendor.mkdir(parents=True, exist_ok=True)

    failures = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [pool.submit(fetch_one, crate, vendor) for crate in crates]

        for future in concurrent.futures.as_completed(futures):
            try:
                print(future.result())
            except Exception as error:  # noqa: BLE001 - report and keep going
                failures += 1
                print(f"!! {error}", file=sys.stderr)

    print(f"done, {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

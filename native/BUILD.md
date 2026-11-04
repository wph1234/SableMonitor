# Building the patched native library

This guide produces a drop-in replacement for the `sable_rapier` native library that ships inside
Sable. Everything below is version-pinned; see [README.md](README.md) for the baseline table.

Tested on Windows (MSVC). Linux and macOS compile the same crate with a different file extension
(`.so` / `.dylib`), and the mod picks the right name by platform.

## 1. Prerequisites

| Tool | Notes |
|---|---|
| Git | |
| Rust | `rustup`. The workspace's `rust-toolchain.toml` pins **`nightly-2026-01-29`**; rustup installs it on first use |
| C linker | Windows: MSVC build tools ("Desktop development with C++"); Linux: `cc` + `binutils`; macOS: Xcode command line tools |
| Python 3 | optional, only for offline vendoring ([step 5](#5-vendor-the-dependencies)) |

`simd-nightly` needs a nightly toolchain; the `profiler` feature needs nothing extra.

## 2. Check out the sources

```bash
export WS="$PWD"                       # or set it to wherever you want the worktree
export SABLE_TAG=mc1.21.1-2.0.6-neoforge
export RAPIER_REV=38e92f117590862481a53df6fc69a5d893e29186

git clone --branch "$SABLE_TAG" --depth 1 https://github.com/ryanhcode/sable  "$WS/.native-src/sable"
git clone https://github.com/ryanhcode/rapier "$WS/.native-src/rapier"
git -C "$WS/.native-src/rapier" checkout "$RAPIER_REV"
```

`$RAPIER_REV` must be the rev that Sable's `sable_rapier/src/main/rust/rapier/Cargo.toml` pins —
otherwise Rapier's internal APIs may not match what the patch expects.

## 3. Apply the patches

```bash
git -C "$WS/.native-src/rapier" apply "$WS/native/patches/rapier-profiling.patch"
git -C "$WS/.native-src/sable"  apply "$WS/native/patches/sable-rapier-profiling.patch"
```

If you would rather apply the Rapier changes by hand, `native/rapier/src/profiling.rs` is a verbatim
copy of the added module. What each patch changes is described in
[rapier/PATCH.md](rapier/PATCH.md) and [sable_rapier/PATCH.md](sable_rapier/PATCH.md).

## 4. Point `sable_rapier` at your Rapier checkout

The patch rewrites the dependency, but the path it writes has to exist on your machine. Use a
**relative** path so the checkout survives being moved:

```toml
# sable_rapier/src/main/rust/rapier/Cargo.toml
# rust/rapier -> rust -> main -> src -> sable_rapier -> sable -> .native-src
rapier3d = { path = "../../../../../../rapier/crates/rapier3d", features = [
    "simd-nightly",
    "parallel",
    "profiler",
] }
```

`profiler` is required for the global stage timings: without it Rapier's `Timer` methods compile to
no-ops and every number reads zero.

## 5. Vendor the dependencies

`cargo` normally downloads crates itself. Two options:

**A. Let cargo do it** (needs network access to crates.io):

```bash
cd "$WS/.native-src/sable/sable_rapier/src/main/rust"
cargo vendor vendor
```

**B. Use the bundled script** (needs only Python; useful behind TLS-inspecting proxies or when
cargo's TLS stack is broken):

```bash
python native/tools/vendor.py \
  "$WS/.native-src/sable/sable_rapier/src/main/rust/Cargo.lock" \
  "$WS/.native-src/sable/sable_rapier/src/main/rust/vendor" 8
```

It verifies every download against the `checksum` recorded in `Cargo.lock` and writes the same
`.cargo-checksum.json` layout as `cargo vendor`.

Either way, the patch already points cargo at the directory:

```toml
# sable_rapier/src/main/rust/.cargo/config.toml
[source.crates-io]
replace-with = "vendored-sources"

[source.vendored-sources]
directory = "vendor"
```

## 6. Build

```bash
cd "$WS/.native-src/sable/sable_rapier/src/main/rust"
cargo build --release --lib
```

| Platform | Artifact |
|---|---|
| Windows | `target/release/sable_rapier.dll` |
| Linux | `target/release/libsable_rapier.so` |
| macOS | `target/release/libsable_rapier.dylib` |

Expect a few minutes on a cold build: `rapier3d`, `parry3d`, `nalgebra` and `glam` are compiled from
source, and the release profile uses `lto = "thin"`, `codegen-units = 1`.

## 7. Verify the exports

The library must keep every symbol Sable's `Rapier3D` declares, plus the two profiling exports.

Windows (Visual Studio developer prompt, or the full path to `dumpbin`):

```powershell
dumpbin /exports target\release\sable_rapier.dll | Select-String "Java_"
# 55 exports; must include:
#   Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_setEnabled0
#   Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_takeProfile0
#   Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_step
```

Linux / macOS:

```bash
nm -D --defined-only target/release/libsable_rapier.so | grep Java_
```

A missing `NativePhysicsProfile_*` symbol means the crate was compiled without the patch (or the
Java package changed and the symbol names went stale — JNI names are derived from the class name).

## 8. Put it into the mod

```bash
cp target/release/sable_rapier.dll \
   "$WS/src/main/resources/natives/sable_rapier/sable_rapier_x86_64_windows.dll"
cd "$WS" && ./gradlew build
```

The file name must be the platform-specific one produced by `PatchedNatives.fileName()`
(`sable_rapier_x86_64_windows.dll`, `sable_rapier_aarch64_macos.dylib`, …).

`src/main/resources/natives/` is git-ignored: the binary is published as a release attachment instead
of being committed, and the build prints a warning when it is missing.

## 9. Check it in game

Client:

```
/sablemonitor profile start
/sablemonitor dump
```

Dedicated server / headless:

```bash
./gradlew runServer -Dsablemonitor.profile=true -Dsablemonitor.profile.log=true
```

Look for these lines in the log:

```
loaded patched rapier natives from <game dir>\.sable\natives\sable_rapier_x86_64_windows-<hash>.dll
probe t=1180 step=0.074ms/tick substeps=2 broad=0.003 collision=0.007 islands=0.000 physics=0.059
```

All `0.000` numbers with the natives loaded means the `profiler` feature is missing. No per-sub-level
rows while `Physics step` is non-zero means the world simply has no sub-level near the player — the
per-body counters are only charged for real pairs and islands.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `SEC_E_NO_CREDENTIALS`, `SSL certificate problem`, `unable to get local issuer certificate` | TLS interception (corporate proxy, antivirus). Use `python native/tools/vendor.py` for crates, and `git -c http.sslBackend=openssl …` or install the proxy's CA in the system store |
| `no matching package named <crate>` while offline | The vendor directory is incomplete; re-run step 5 (the script reports per-crate failures) |
| `stream did not contain valid UTF-8` | A source file was written by a tool using the ANSI code page. Re-save it as UTF-8 |
| `AccessDeniedException` / `The process cannot access the file` on reload | A running game still holds the DLL. `PatchedNatives` extracts to a content-addressed name to avoid this; close the stale process if it persists |
| `UnsatisfiedLinkError: NativePhysicsProfile.setEnabled0` | The loaded library predates the patch, or the Java package was renamed without rebuilding |
| `missing manifest in toolchain` | An interrupted rustup download left a broken toolchain: `rustup toolchain uninstall nightly-2026-01-29`, remove leftovers, reinstall |

## Reproducibility

The `profiler` feature and the release profile (`lto = "thin"`, `codegen-units = 1`) are already set
up by the pinned workspace. The MSVC linker still stamps a timestamp into the PE header, so two
builds of the same source differ in bytes. Pass `-C link-arg=/Brepro` (MSVC) or
`-C link-arg=-Wl,-Brepro` (GNU targets) if you need bit-identical output; the hashes published on the
release page identify our artifacts, not a value your machine is guaranteed to reproduce.

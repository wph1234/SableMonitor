# 第三方许可与归属 / Third-Party Notices

SableMonitor 本身以 MIT 发布（见 `LICENSE`）。但**发布物**（尤其是 `sable_rapier_*.dll`
这个原生库）包含或静态链接了下面这些第三方作品，它们各有自己的许可。

> The prebuilt `sable_rapier_*.dll` release attachment is **not** MIT: it is a modified
> build of third-party code. See sections 1–3 below.

---

## 1. Sable — PolyForm Shield 1.0.0

- Project: **Sable** — <https://github.com/ryanhcode/sable>
- Copyright © RyanHCode and Sable contributors
- License: **PolyForm Shield License 1.0.0** — <https://polyformproject.org/licenses/shield/1.0.0>
  (full text: `licenses/PolyForm-Shield-1.0.0.txt`)

**What we redistribute.** `sable_rapier_<arch>_<os>.dll` is a **modified build** of Sable's own
`sable_rapier` native crate. It is compiled from the source of Sable tag
`mc1.21.1-2.0.6-neoforge` with the instrumentation patches in `native/` applied:

- `native/sable_rapier/PATCH.md` — what was changed and why
- `native/patches/sable-rapier-profiling.patch` — the exact diff
- `native/README.md` — build steps, toolchain and dependency pinning

**Modifications** (so anyone receiving the binary knows what changed): added per-sub-level
profiling slots, a tick-scoped profiling window, two extra JNI exports
(`NativePhysicsProfile.setEnabled0` / `takeProfile0`), and switched `rapier3d` to a locally
patched path dependency that enables the `profiler` feature.

**What we do not redistribute.** Sable itself (the mod jar) is *not* bundled. Users install
Sable separately; SableMonitor refuses to load unless Sable 2.0.6 is present
(`versionRange = "[2.0.6]"` in `META-INF/neoforge.mods.toml`).

**Noncompete.** PolyForm Shield forbids using the software to provide a product that competes
with Sable. SableMonitor is an instrumentation add-on: it requires Sable, does not reimplement
any of its functionality, and is not usable as a substitute. It is not affiliated with or
endorsed by the Sable author.

The license text (or its URL) and this attribution must accompany every copy of the DLL, as
required by the "Notices" section of PolyForm Shield 1.0.0. Sable's license contains no
`Required Notice:` lines.

## 2. Rapier / Parry / nalgebra — Apache-2.0

- **rapier** — <https://github.com/dimforge/rapier>; the fork used here is
  <https://github.com/ryanhcode/rapier> at commit `38e92f1`
- **parry**, **nalgebra**, **simba**, **glam** — same authors, also Apache-2.0
- Copyright © Dimforge and contributors
- License: **Apache License 2.0** — full text in `licenses/Apache-2.0.txt`

**Modifications.** We changed Rapier's source (`native/patches/rapier-profiling.patch`):

- added `src/profiling.rs` (slot table, `PairGuard`, `IslandGuard`, `take*()`)
- `src/lib.rs`: `pub mod profiling;`
- `src/geometry/narrow_phase.rs`: per-collider-pair timer in `compute_contacts`
- `src/pipeline/physics_pipeline.rs`: per-island solver timer

Every changed site is marked with `>>> SableMonitor >>>` … `<<< SableMonitor <<<` comments, as
required by Apache-2.0 §4(b) ("prominent notices stating that You changed the files"). This
notice also serves as the required statement of modification.

## 3. Rust crates statically linked into the DLL

The DLL statically links the crates listed in `Cargo.lock` (170 crates, vendored under
`.native-src/.../vendor/` at build time, not part of this repository). License distribution:

| License expression | Crates |
|---|---|
| MIT OR Apache-2.0 (+ variants) | 133 |
| MIT only | 16 |
| Apache-2.0 only | 8 |
| Unlicense OR MIT | 8 |
| Zlib OR Apache-2.0 OR MIT | 3 |
| Zlib only | 2 |
| MPL-2.0 | 2 (`colored` 1.9.4, `colored` 2.2.0) |
| BSD-2-Clause OR Apache-2.0 OR MIT | 2 |
| (MIT OR Apache-2.0) AND Unicode-3.0 | 1 (`unicode-ident`) |

For all MIT / Apache-2.0 / Zlib / BSD-2 / Unlicense crates the obligations are satisfied by
reproducing the license text (`licenses/Apache-2.0.txt`, `licenses/Zlib.txt`, and the MIT text
in `LICENSE`) plus the attribution below. Each crate's own copyright line is in its source
distribution on <https://crates.io>; nothing was modified.

MPL-2.0 note: `colored` is used unmodified, only through its public API, so no source
disclosure obligation applies beyond shipping its license text (`licenses/MPL-2.0.txt`).

Unicode-3.0 note: `unicode-ident` is used unmodified; see `licenses/Unicode-3.0.txt`.

<!-- BEGIN GENERATED: do not edit by hand, regenerate with native/tools/notices.py -->

> Crates linked into the release build of `sable_rapier` (83 entries), excluding dev-dependencies such as `criterion`.

### 3.1 Crate list

| crate | version | license |
|---|---|---|
| `approx` | 0.5.1 | Apache-2.0 |
| `arrayvec` | 0.7.6 | MIT OR Apache-2.0 |
| `bitflags` | 2.11.0 | MIT OR Apache-2.0 |
| `bytemuck` | 1.25.0 | Zlib OR Apache-2.0 OR MIT |
| `byteorder` | 1.5.0 | Unlicense OR MIT |
| `bytes` | 1.11.1 | MIT |
| `cesu8` | 1.1.0 | Apache-2.0/MIT |
| `cfg-if` | 1.0.4 | MIT OR Apache-2.0 |
| `colored` | 1.9.4 | MPL-2.0 |
| `colored` | 2.2.0 | MPL-2.0 |
| `combine` | 4.6.7 | MIT |
| `crossbeam-deque` | 0.8.6 | MIT OR Apache-2.0 |
| `crossbeam-epoch` | 0.9.18 | MIT OR Apache-2.0 |
| `crossbeam-utils` | 0.8.21 | MIT OR Apache-2.0 |
| `dashmap` | 7.0.0-rc2 | MIT |
| `downcast-rs` | 2.0.2 | MIT OR Apache-2.0 |
| `either` | 1.15.0 | MIT OR Apache-2.0 |
| `ena` | 0.14.4 | MIT OR Apache-2.0 |
| `equivalent` | 1.0.2 | Apache-2.0 OR MIT |
| `fern` | 0.6.2 | MIT |
| `foldhash` | 0.2.0 | Zlib |
| `glam` | 0.30.10 | MIT OR Apache-2.0 |
| `glamx` | 0.1.3 | MIT OR Apache-2.0 |
| `hash32` | 0.3.1 | MIT OR Apache-2.0 |
| `hashbrown` | 0.15.5 | MIT OR Apache-2.0 |
| `hashbrown` | 0.16.1 | MIT OR Apache-2.0 |
| `heapless` | 0.8.0 | MIT OR Apache-2.0 |
| `humantime` | 2.3.0 | MIT OR Apache-2.0 |
| `is-terminal` | 0.4.17 | MIT |
| `jni` | 0.21.1 | MIT/Apache-2.0 |
| `jni-sys` | 0.3.1 | MIT OR Apache-2.0 |
| `jni-sys` | 0.4.1 | MIT OR Apache-2.0 |
| `jni-sys-macros` | 0.4.1 | MIT OR Apache-2.0 |
| `lazy_static` | 1.5.0 | MIT OR Apache-2.0 |
| `libm` | 0.2.16 | MIT |
| `lock_api` | 0.4.14 | MIT OR Apache-2.0 |
| `log` | 0.4.29 | MIT OR Apache-2.0 |
| `matrixmultiply` | 0.3.10 | MIT/Apache-2.0 |
| `memchr` | 2.8.0 | Unlicense OR MIT |
| `nalgebra` | 0.34.1 | Apache-2.0 |
| `nalgebra-macros` | 0.3.0 | Apache-2.0 |
| `num-complex` | 0.4.6 | MIT OR Apache-2.0 |
| `num-derive` | 0.4.2 | MIT OR Apache-2.0 |
| `num-integer` | 0.1.46 | MIT OR Apache-2.0 |
| `num-rational` | 0.4.2 | MIT OR Apache-2.0 |
| `num-traits` | 0.2.19 | MIT OR Apache-2.0 |
| `ordered-float` | 5.1.0 | MIT |
| `parking_lot_core` | 0.9.12 | MIT OR Apache-2.0 |
| `parry3d` | 0.26.0 | Apache-2.0 |
| `paste` | 1.0.15 | MIT OR Apache-2.0 |
| `proc-macro2` | 1.0.106 | MIT OR Apache-2.0 |
| `profiling` | 1.0.17 | MIT OR Apache-2.0 |
| `profiling-procmacros` | 1.0.17 | MIT OR Apache-2.0 |
| `quote` | 1.0.45 | MIT OR Apache-2.0 |
| `rawpointer` | 0.2.1 | MIT/Apache-2.0 |
| `rayon` | 1.11.0 | MIT OR Apache-2.0 |
| `rayon-core` | 1.13.0 | MIT OR Apache-2.0 |
| `rstar` | 0.12.2 | MIT OR Apache-2.0 |
| `safe_arch` | 0.7.4 | Zlib OR Apache-2.0 OR MIT |
| `scopeguard` | 1.2.0 | MIT OR Apache-2.0 |
| `simba` | 0.9.1 | Apache-2.0 |
| `slab` | 0.4.12 | MIT |
| `smallvec` | 1.15.1 | MIT OR Apache-2.0 |
| `stable_deref_trait` | 1.2.1 | MIT OR Apache-2.0 |
| `static_assertions` | 1.1.0 | MIT OR Apache-2.0 |
| `syn` | 2.0.117 | MIT OR Apache-2.0 |
| `thiserror` | 1.0.69 | MIT OR Apache-2.0 |
| `thiserror` | 2.0.18 | MIT OR Apache-2.0 |
| `thiserror-impl` | 1.0.69 | MIT OR Apache-2.0 |
| `thiserror-impl` | 2.0.18 | MIT OR Apache-2.0 |
| `typenum` | 1.19.0 | MIT OR Apache-2.0 |
| `unicode-ident` | 1.0.24 | (MIT OR Apache-2.0) AND Unicode-3.0 |
| `web-time` | 1.1.0 | MIT OR Apache-2.0 |
| `wide` | 0.7.33 | Zlib OR Apache-2.0 OR MIT |
| `winapi` | 0.3.9 | MIT/Apache-2.0 |
| `windows-link` | 0.2.1 | MIT OR Apache-2.0 |
| `windows-sys` | 0.45.0 | MIT OR Apache-2.0 |
| `windows-sys` | 0.59.0 | MIT OR Apache-2.0 |
| `windows-sys` | 0.61.2 | MIT OR Apache-2.0 |
| `windows-targets` | 0.42.2 | MIT OR Apache-2.0 |
| `windows-targets` | 0.52.6 | MIT OR Apache-2.0 |
| `windows_x86_64_msvc` | 0.42.2 | MIT OR Apache-2.0 |
| `windows_x86_64_msvc` | 0.52.6 | MIT OR Apache-2.0 |

<!-- END GENERATED -->

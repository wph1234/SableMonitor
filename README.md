# Sable Monitor

Per-sub-level physics profiling for [Sable](https://github.com/ryanhcode/sable), measured **inside**
Rapier's Rust step instead of guessed from the outside.

Sable turns every sub-level into one dynamic rigid body plus one voxel collider, and steps all of
them through a single `PhysicsPipeline`. From Java you can only see the total cost of that step.
Sable Monitor ships a patched `sable_rapier` native library that times the two hot stages per
collider pair / per island and attributes them back to the sub-level that owns the body, then
exposes the numbers through `/sablemonitor`.

```
/sablemonitor dump
--------------------------------------------------
Physics step 0.164 ms/tick (2 substep(s), 0.082 ms/substep)
[broad 0.003 ms | collision 0.009 ms | islands 0.001 ms | physics 0.147 ms | ccd 0.000 ms]
collision*  physics*    total*   pairs  island
17d3f0a1-… 0.031ms   0.284ms   0.315ms    9       2
```

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.264 or newer |
| **Sable** | **exactly 2.0.6** (`versionRange = "[2.0.6]"`) — the bundled natives are built from that tag |

Sable is a hard dependency and is **not** bundled. Install it next to Sable Monitor.

## Usage

| Command | |
|---|---|
| `/sablemonitor profile start` \| `stop` | turn sampling on / off |
| `/sablemonitor dump` | last complete tick: global stage timings + the 10 most expensive sub-levels |
| `/sablemonitor sublevels <selector>` | same numbers for the sub-levels you select (`@e`, a UUID, …), capped at 10 |

Requires permission level 2. Clicking a sub-level UUID fills a `/execute in … run tp` command into
your chat bar.

Headless servers can skip the commands:

```
-Dsablemonitor.profile=true          # enable sampling at server start
-Dsablemonitor.profile.log=true      # log the table every 20 ticks
```

## How to read the numbers

| Column | What it is |
|---|---|
| `Physics step` | wall-clock time of `PhysicsPipeline::step`, summed over the tick's substeps |
| `broad` / `islands` / `ccd` | broad phase / island construction / CCD, same tick window |
| `collision` | narrow phase: contact generation for the collider pairs this sub-level takes part in |
| `physics` | solver: constraint solving for the island(s) this sub-level belongs to |
| `pairs` | collider pairs involving this sub-level in this tick |
| `island` | body count of the island it was solved in (>2 means it is connected to something else) |

`collision` and `physics` per sub-level are **thread-time sums**: every pair is timed on whichever
Rayon worker ran it, and a tick runs several substeps. They are only valid for ranking sub-levels
against each other — they are not wall-clock and do not add up to the `Physics step` line.

## How it works

1. **Rapier** (`native/patches/rapier-profiling.patch`): a `profiling` module plus two drop guards,
   one per collider pair in `NarrowPhase::compute_contacts`, one per island solve in
   `PhysicsPipeline::step`. Samples are relaxed `AtomicU64` counters.
2. **sable_rapier** (`native/patches/sable-rapier-profiling.patch`): each sub-level gets a
   process-wide profiling slot, stored in the rigid body's `user_data`, so a pair/island sample can
   be attributed to exactly one sub-level. A tick-scoped window publishes one complete tick at a
   time, and two extra JNI exports (`setEnabled0`, `takeProfile0`) read it.
3. **This mod**: a mixin cancels Sable's own native loader and loads the patched library instead;
   the command layer maps runtime ids back to sub-levels and formats the table.

The native side is documented in [`native/README.md`](native/README.md) — build steps, toolchain,
pinned revisions and every patch.

## Version matrix

The mod version encodes the Sable version its natives were built from.

| Sable Monitor | Sable | patched DLL |
|---|---|---|
| 0.5.1-alpha-sable2.0.6 | 2.0.6 | native bundle identical to 2.0.5 → same DLL still applies |

## Building

Java only (the jar will not contain natives, and `/sablemonitor` will report no data):

```bash
./gradlew build --offline
```

Full build, including the patched native library, is documented step by step in
[`native/README.md`](native/README.md) and [`native/BUILD.md`](native/BUILD.md). In short:

1. `rustup` + a Rust **nightly** toolchain (`nightly-2026-01-29`) and MSVC build tools.
2. Clone `ryanhcode/sable` at tag `mc1.21.1-2.0.6-neoforge` and `ryanhcode/rapier` at `38e92f1`.
3. Apply `native/patches/rapier-profiling.patch` and `native/patches/sable-rapier-profiling.patch`.
4. Vendor the crates (`native/tools/vendor.py`) and `cargo build --release --lib`.
5. Copy `sable_rapier.dll` to `src/main/resources/natives/sable_rapier/sable_rapier_x86_64_windows.dll`.
6. `./gradlew build`.

The prebuilt DLL is a **release attachment only** — it is not committed to this repository
(see `.gitignore`), because it is a 1.7 MB binary per Sable version.

## License and attribution

- **Sable Monitor's own code**: MIT, see [`LICENSE`](LICENSE).
- **The prebuilt native library is *not* MIT.** It is a modified build of third-party code:
  - `sable_rapier` originates from **Sable**, licensed under the **PolyForm Shield 1.0.0**
    (<https://polyformproject.org/licenses/shield/1.0.0>), © RyanHCode and Sable contributors.
    Modifications are documented in `native/` (patches + build notes). Every copy of the DLL must
    be accompanied by that license text or URL and this attribution.
  - Rapier / Parry / nalgebra / glam are **Apache-2.0**, © Dimforge and contributors. The Rapier
    sources used here were modified; all changed sites carry `>>> SableMonitor >>>` markers.
  - 80+ Rust crates are statically linked; their licenses are listed in
    [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), with the license texts under `licenses/`.

Sable Monitor is an instrumentation add-on: it **requires** Sable, reimplements none of its
functionality, and is not a substitute for it (PolyForm Shield's noncompete term). It is not
affiliated with or endorsed by the Sable author.

## 中文摘要

- 目的：在 Rust 层给 Sable 的每个 sub-level 物理开销计时（narrow phase 按 collider pair、solver 按
  island 归属到 sub-level），再通过 `/sablemonitor` 显示。
- 依赖 **Sable 2.0.6（精确版本）**，本体不打包，需要单独安装。
- 本仓库代码 MIT；但随 release 提供的 `sable_rapier_*.dll` 是 Sable（PolyForm Shield 1.0.0）与
  Rapier（Apache-2.0）的**修改版构建**，必须随附 `licenses/` 里的许可与
  [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)，且本 mod 不得与 Sable 构成竞争。
- 逐列含义、重建步骤见 [`native/README.md`](native/README.md)。

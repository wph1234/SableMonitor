# Patched native library

Sable Monitor answers one question that Java cannot: **which sub-level is burning the physics
budget?** Sable steps every sub-level through a single `PhysicsPipeline`, so from the JVM you only
see the total cost of the step. This directory holds the two patches that make Rapier time its own
work per body, and the build guide for turning them into a drop-in replacement for Sable's
`sable_rapier` native library.

The prebuilt library is **not** in this repository — it is a ~1.7 MB binary per Sable version and
is published as a release attachment. See [BUILD.md](BUILD.md) to reproduce it.

```
sable_rapier_x86_64_windows.dll
  built from: sable@mc1.21.1-2.0.6-neoforge + rapier@38e92f1 + native/patches/*
  size:       ~1.7 MB (1,700,352 bytes for the current build)
```

The SHA-256 of each shipped build is listed on the release page, next to the attachment. MSVC builds
are not bit-reproducible by default (the linker stamps a timestamp), so treat the hash as an
identifier for one release artifact, not as a value another machine will reproduce.

## What the patches do

| Patch | Upstream | Touches |
|---|---|---|
| [`rapier-profiling.patch`](patches/rapier-profiling.patch) | [`ryanhcode/rapier`](https://github.com/ryanhcode/rapier) `38e92f1` | new `src/profiling.rs`; `src/lib.rs`; `src/geometry/narrow_phase.rs`; `src/pipeline/physics_pipeline.rs` |
| [`sable-rapier-profiling.patch`](patches/sable-rapier-profiling.patch) | [`ryanhcode/sable`](https://github.com/ryanhcode/sable) `mc1.21.1-2.0.6-neoforge` | `sable_rapier/src/main/rust/rapier/{Cargo.toml,src/scene.rs,src/lib.rs}`; `.cargo/config.toml`; `Cargo.lock` |

Details: [rapier/PATCH.md](rapier/PATCH.md) and [sable_rapier/PATCH.md](sable_rapier/PATCH.md).

## How a sample is attributed

Rapier has no concept of a "sub-level", so attribution rides on data it already has:

1. **Slots.** When a sub-level is created, `sable_rapier` allocates a slot from a process-wide pool
   and writes it into the rigid body's `user_data` (`RigidBody::user_data as u128`). Bodies with
   `user_data == 0` are "the rest of the world" (terrain, kinematic contraptions, boxes, ropes).
2. **Narrow phase.** Every collider pair that actually runs `compute_contacts` is timed. When the two
   colliders belong to different slots the cost is split 50/50 between them — including the untagged
   slot 0 — and when both belong to the same slot the whole cost goes to it. Every slot involved
   records the pair in its `pairs` counter, so a sub-level resting on terrain still reports those
   contacts.
3. **Solver.** Every island solve is timed once and split evenly between the island's *tagged*
   bodies (slot 0 is not a divisor — otherwise a ground contact would dilute every share). The
   island's body count is recorded as `island_max`.
4. **Scene ownership.** Slots are process-wide, but samples are consumed per scene: a scene only
   takes the slots it owns, so the overworld, nether and end never mix numbers.

Both timers are RAII guards (`PairGuard`, `IslandGuard`) rather than "start/stop" pairs, because the
pair loop has early `return`s and a labelled `break`; a guard records the elapsed time on drop, so no
branch can lose a sample.

## Tick window

Rapier resets its `Counters` at the start of every `PhysicsPipeline::step`, and one game tick can run
several substeps (CCD). So the numbers are **accumulated per tick and published when the tick
ends**:

- `Rapier3D.step()` — adds this substep's stage timings to the in-flight accumulator, `substeps += 1`.
- `Rapier3D.tick()` — takes this scene's slot samples, then publishes the in-flight tick as `last`
  (only if at least one substep ran).
- `takeProfile0()` — reads `last` **without clearing it**, so calling `/sablemonitor dump` twice in
  one tick shows the same numbers instead of the second call showing zeros.

## Reading the numbers

| Field | Meaning | Unit |
|---|---|---|
| `step` | wall-clock time of `PhysicsPipeline::step`, summed over the tick's substeps | ms |
| `broad` | broad phase | ms |
| `collision` | narrow phase (contact generation) | ms |
| `islands` | island construction | ms |
| `physics` | constraint solver | ms |
| `ccd` | CCD | ms |
| `pairs` | collider pairs involving this sub-level in this tick | count |
| `island` | bodies in the island it was solved in (>2 means it is connected to something) | count |

`step`/`broad`/`collision`/`islands`/`physics`/`ccd` in the summary line are wall-clock: they come
from Rapier's own `Counters` and add up. The **per-sub-level** `collision` and `physics` columns are
thread-time sums: each pair is timed on whichever Rayon worker ran it, and a tick runs several
substeps. They rank sub-levels against each other; they are not wall-clock and do not sum to the
summary line.

## Upstream baseline

| | |
|---|---|
| Sable | tag `mc1.21.1-2.0.6-neoforge` |
| Rapier | `ryanhcode/rapier` rev `38e92f117590862481a53df6fc69a5d893e29186` (the rev Sable pins) |
| Rust | `nightly-2026-01-29` (Sable's `rust-toolchain.toml`) |
| Features | `simd-nightly`, `parallel`, `profiler` |

`profiler` is not cosmetic: without it Rapier's `Timer` compiles to a no-op and every global stage
timing reads `0.000 ms`. It is also the reason `web-time` had to be vendored.

## Version compatibility

The bundled native library replaces Sable's, so it must match the Sable version the mod targets.
Between 2.0.5 and 2.0.6 nothing on the native side changed:

| Check | Result |
|---|---|
| `natives/sable_rapier/sable_rapier_binaries.zip.l4z` SHA-256 | identical in both jars (`986d1edc…`) |
| `git diff 2.0.5..2.0.6 -- sable_rapier/src/main/rust` | empty |
| Java side | one unrelated file (`TheFasterEntityCollisionContext`) |

So a library built from 2.0.5 also serves 2.0.6; a Sable release that changes the native code needs a
rebuild.

## Layout

| Path | |
|---|---|
| `patches/*.patch` | the exact diffs, applied with `git apply` |
| `rapier/src/profiling.rs` | reference copy of the module added to Rapier |
| `rapier/PATCH.md` | what the Rapier patch does, hunk by hunk |
| `sable_rapier/PATCH.md` | what the `sable_rapier` patch does (slots, tick window, JNI) |
| `BUILD.md` | reproducible build, verification and troubleshooting |
| `tools/vendor.py` | offline dependency vendoring (no cargo network access required) |
| `tools/notices.py` | regenerates the crate table in `../THIRD-PARTY-NOTICES.md` |

## Releasing the prebuilt library

1. Build it ([BUILD.md](BUILD.md)) and copy `sable_rapier.dll` to
   `src/main/resources/natives/sable_rapier/sable_rapier_x86_64_windows.dll`.
2. `./gradlew build` — the jar embeds it, together with the licence files (see below).
3. Attach the DLL to the GitHub release as well, with its SHA-256, so people who build the mod
   themselves do not have to set up a Rust toolchain.

## License

The library is a **modified build of third-party code**, not MIT:

- `sable_rapier` comes from Sable — **PolyForm Shield 1.0.0**, © RyanHCode and Sable contributors.
- Rapier / Parry / nalgebra / glam — **Apache-2.0**, © Dimforge and contributors.
- 83 Rust crates are statically linked; their licenses are listed in
  [`../THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md), texts in [`../licenses/`](../licenses).

Every distribution of the DLL must carry that licence text or URL plus the attribution. `gradlew
build` packages `LICENSE`, `THIRD-PARTY-NOTICES.md` and `licenses/` into the jar's `META-INF/`.

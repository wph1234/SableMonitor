# Rapier patch: per-pair and per-island timers

Adds the measurement points to [`rapier`](https://github.com/ryanhcode/rapier) and nothing else:
one new module plus three one-line insertions. No new crate dependencies.

| File | Change |
|---|---|
| `src/profiling.rs` | **new** — slot tables, RAII guards, `take*()` readers |
| `src/lib.rs` | `pub mod profiling;` |
| `src/geometry/narrow_phase.rs` | one guard per collider pair |
| `src/pipeline/physics_pipeline.rs` | one guard per island solve |

Apply it directly:

```bash
git -C .native-src/rapier apply native/patches/rapier-profiling.patch
```

The patch is generated against Rapier rev `38e92f1` (the rev Sable pins). `native/rapier/src/profiling.rs`
is a verbatim copy of the added module if you prefer to copy it in and edit the three call sites by
hand.

## Attribution model

Rapier has no idea what a "sub-level" is. The host tags a rigid body by writing a small integer into
`RigidBody::user_data`; everything else is derived from that.

- `MAX_SLOTS = 1 << 16` — slot 0 is the "world"/untagged bucket, hosts allocate from 1 upward.
- `MAX_ISLAND_TAGS = 16` — at most 16 distinct tagged bodies per island are *credited*; islands with
  more still get their time divided by the real number of distinct tagged bodies.
- `profiling::set_enabled(bool)` flips a single `AtomicBool`. While it is off, a sample point costs
  one relaxed load and never calls `Instant::now()`.
- Enabling pre-allocates the tables (four arrays of 65,536 atomics, ~1.5 MiB) on the calling thread,
  so the first physics step does not pay for it.

### Narrow phase (per collider pair)

```rust
// src/geometry/narrow_phase.rs, inside compute_contacts' par_iter_mut over edges
let _profile_pair = crate::profiling::pair_guard(bodies, rb_handle1, rb_handle2);
```

`PairGuard::drop` records `elapsed()` into relaxed counters:

| Case | `narrow_ns` | `pairs` |
|---|---|---|
| both endpoints tagged, different slots (`a != b`) | `t/2` to `a`, `t - t/2` to `b` | `a += 1`, `b += 1` |
| same slot on both sides | `t` to `a` | `a += 1` |
| one side untagged (world geometry, slot 0) | `t/2` to the tagged slot, `t/2` to slot 0 | tagged slot `+= 1`, slot 0 `+= 1` |
| both untagged | `t` to slot 0 | slot 0 `+= 1` |

In other words: the time always follows the two *slots* that produced the pair, 50/50 unless both
ends are the same body slot. The world bucket (slot 0) absorbs its half and is filtered out of the
per-sub-level report, so a sub-level that spends half its contacts against terrain genuinely shows
half the pair cost.

A global `TOTAL_PAIRS` counter also increments once per pair. The per-slot counters are double
counted by construction (each pair credits both endpoints), so the total is only available from
`take_total_pairs()`.

`pair_guard` is an RAII guard **on purpose**: the pair loop has early `return`s and a labelled
`break 'emit_events`, so "time it, then add at the end of the closure" would silently lose samples.
Drop always runs.

The parameter is `&RigidBodySet` while the call site holds `&mut RigidBodySet`; the automatic
reborrow is what makes that compile without `&*`.

Do **not** add the same guard to `compute_intersections`: sensor intersections are not part of the
sub-level physics load and would pollute the numbers.

### Solver (per island)

```rust
// src/pipeline/physics_pipeline.rs, parallel velocity solve closure
let _profile_island = crate::profiling::island_guard(islands, island_id, &*bodies);
```

`island_guard` snapshots the island's tagged slots at construction (it does **not** keep borrowing
`bodies`), so the later `solver.init_and_solve(…, bodies, …)` mutable borrow is still fine.
On drop it splits the elapsed time evenly across the island's distinct tagged bodies:

```text
share = elapsed / distinct_tagged_bodies
solver_ns[slot] += share            (for the first 16 tagged slots)
island_max[slot] = max(island_max[slot], island_body_count)
```

Slot 0 is excluded from the divisor: a sub-level resting on the ground must not have its share
diluted by the static world body. `island_max` uses `fetch_max`, so it reports the largest island the
sub-level was part of during the tick.

Sable always builds with `features = ["parallel"]`, so the `#[cfg(not(feature = "parallel"))]` branch
is dead for our use case. If you need serial builds, insert the same line in that branch (before
`self.solvers[..].init_and_solve`).

## Readers

| Function | Behaviour |
|---|---|
| `take()` | swaps every slot to 0 and returns the non-empty ones (see `reset()`) |
| `take_filtered(owner)` | same, but only for slots matching the predicate. `sable_rapier` uses it to consume only the current scene's slots |
| `take_total_pairs()` | swap-and-read of the global pair counter |
| `reset()` | zeroes everything without allocating |

All readers are `O(MAX_SLOTS)` (65,536 relaxed swaps, tens of microseconds), called once per game
tick by the host — never from inside the physics step.

## Requirements and notes

- **`profiler` feature.** The global stage timings (`step`, broad phase, narrow phase, islands,
  solver, CCD) come from Rapier's own `Counters`, whose `Timer` methods are no-ops without that
  feature. `sable_rapier` enables it in its `Cargo.toml`.
- **`alloc_prelude`.** The module starts with `use crate::alloc_prelude::*;` because Rapier is
  `no_std`-capable and `Vec` is not in scope by default.
- **Overhead.** Enabled: two `Instant::now()` calls plus two relaxed `fetch_add`s per collider pair,
  and one clock pair per island. Disabled: a single relaxed `AtomicBool::load` per sample point.
- **Unit test.** `attributes_time_to_body_slots` steps two tagged, overlapping balls through a real
  `PhysicsPipeline` and asserts that both slots report pairs, narrow-phase time, solver time and a
  shared island — i.e. it exercises the attribution, not just the arithmetic.

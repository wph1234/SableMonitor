# `sable_rapier` patch: attribution, tick window, JNI export

This is the second half of the measurement: it takes Rapier's per-slot samples, keeps them aligned
to game ticks, and hands them to Java. Everything happens inside Sable's own
`sable_rapier/src/main/rust/rapier/` crate.

| File | Change |
|---|---|
| `Cargo.toml` | `rapier3d` points at the locally patched copy and enables `profiler` |
| `.cargo/config.toml` | route `crates-io` through a vendored directory |
| `src/scene.rs` | per-sub-level slot bookkeeping + tick-scoped accumulators |
| `src/lib.rs` | process-wide slot pool, `user_data` tagging, tick window, two JNI exports |

Apply it directly:

```bash
git -C .native-src/sable apply native/patches/sable-rapier-profiling.patch
```

## 1. `Cargo.toml` — use the patched Rapier

```toml
rapier3d = { path = "../../../../../../rapier/crates/rapier3d", features = [
    "simd-nightly",
    "parallel",
    "profiler",
] }
```

`profiler` is what makes Rapier's `Counters` do anything; without it the global stage timings are
all zero. The path is relative to `rust/rapier/` so the checkout can be moved freely — adjust it if
your layout differs.

## 2. `.cargo/config.toml` — offline builds

```toml
[source.crates-io]
replace-with = "vendored-sources"

[source.vendored-sources]
directory = "vendor"
```

Populate `vendor/` with `cargo vendor vendor`, or with
[`../tools/vendor.py`](../tools/vendor.py) when cargo cannot reach crates.io. The `profiler` feature
pulls in `web-time`, which is not part of Sable's original `Cargo.lock` — the lock file is patched as
well.

## 3. `src/scene.rs` — slots and the tick window

### Slot bookkeeping on `SableSceneData`

```rust
/// profiling slot -> sable sub-level id
pub profile_slot_ids: HashMap<u32, i32>,
/// sable sub-level id -> profiling slot
pub profile_slot_of: HashMap<LevelColliderID, u32>,
```

```rust
impl SableSceneData {
    pub fn alloc_profile_slot(&mut self, id: LevelColliderID) -> u32 { /* crate::alloc_slot() */ }
    pub fn free_profile_slot(&mut self, id: LevelColliderID) { /* returns the slot to the pool */ }
    pub fn owns_profile_slot(&self, slot: u32) -> bool { /* slot != 0 && slot in profile_slot_ids */ }
}
```

Slots come from a **process-wide** pool (`crate::alloc_slot`), not from a per-scene `Vec`. Two
scenes (overworld, nether, …) would otherwise both allocate slot 1 and a single slot would mean two
different bodies in Rapier's global counters. `owns_profile_slot` is what lets a scene consume only
its own samples.

### Tick-scoped accumulators

```rust
pub struct GlobalAccum { step_ms, broad_ms, narrow_ms, island_ms, solver_ms, ccd_ms, update_ms }
pub struct ProfileTick { global: GlobalAccum, slots: Vec<SlotSample>, substeps: u32 }
pub struct ProfileState { current: GlobalAccum, current_substeps: u32, last: ProfileTick }
```

`GlobalAccum::add(&Counters)` sums one substep's stage timings. `ProfileState::finish_tick(samples)`
publishes the in-flight tick as `last` — but only if at least one substep ran, so a paused physics
scene keeps showing the last real numbers instead of a row of zeros.

`PhysicsScene` gets one extra field:

```rust
pub profile: std::sync::Mutex<ProfileState>,
```

A `Mutex` is enough: `step()` holds a shared reference to the scene, and the lock is only taken once
per substep and once per tick.

## 4. `src/lib.rs` — tagging, tick window, export

### Process-wide slot pool

```rust
pub struct SlotPool { /* next: u32, free: Vec<u32> */ }
static SLOT_POOL: Mutex<SlotPool> = …;

pub fn alloc_slot() -> Option<u32> { /* starts at 1; 0 is the world bucket */ }
pub fn free_slot(slot: u32) { /* returns it to the pool */ }
```

`alloc_profile_slot` falls back to `0` when the pool is exhausted (65,536 slots): the sub-level then
merges into the "world" bucket instead of breaking.

### Tagging a sub-level

```rust
// createSubLevel
let profile_slot = sable_data.alloc_profile_slot(id as LevelColliderID);
rigid_body.user_data = profile_slot as u128;
```

```rust
// removeSubLevel
sable_data.free_profile_slot(id as LevelColliderID);
```

`RigidBody::user_data` is a public `u128` field, so no Rapier change is needed for this.

### Tick window

```rust
// tick() — runs once per game tick, before that tick's substeps
let samples = {
    let sable_data = scene.sable_data.read().unwrap();
    rapier3d::profiling::take_filtered(|slot| sable_data.owns_profile_slot(slot))
};
scene.profile.lock().unwrap().finish_tick(samples);
```

```rust
// step() — one substep, after PhysicsPipeline::step(…)
let mut profile = scene.profile.lock().unwrap();
profile.current.add(&sim.pipeline.counters);
profile.current_substeps += 1;
```

Why this shape: Rapier resets `Counters` at the start of every step, and a tick can run several
substeps. Accumulating in `step` and publishing in `tick` gives exactly one complete tick per
publication, never a partial one.

### JNI exports

```rust
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_setEnabled0(
    _env: JNIEnv, _class: JClass, enabled: jboolean,
) { rapier3d::profiling::set_enabled(enabled != 0); }

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_takeProfile0(
    env: JNIEnv, _class: JClass, handle: jlong,
) -> JDoubleArray { /* read `last`, do not clear it */ }
```

Symbol names are derived from the Java class, so they must match
`dev.wphuujmhu.sablemonitor.natives.NativePhysicsProfile` exactly. Renaming the Java package without
rebuilding the DLL produces `UnsatisfiedLinkError` at runtime — nothing else.

Payload layout (all `double`):

| Index | Value |
|---|---|
| 0 | `step_ms` — wall clock of the whole `PhysicsPipeline::step`, summed over substeps |
| 1 | `broad_ms` |
| 2 | `narrow_ms` |
| 3 | `island_ms` |
| 4 | `solver_ms` |
| 5 | `ccd_ms` |
| 6 | `update_ms` |
| 7 | `substeps` — how many `step` calls this tick made |
| 8 | `recordCount` |

Then `recordCount` records of 5 values: `id` (the sub-level's runtime id), `narrow_ns`, `solver_ns`,
`pairs`, `island_max`.

The read is **non-destructive**: `/sablemonitor dump` twice in the same tick shows the same numbers.
Slot 0 is filtered out here — it is the untagged world bucket, already covered by the global timings.

## Notes

- **Only dynamic sub-levels are tagged.** Kinematic contraptions, boxes and ropes keep
  `user_data == 0`, so their cost lands in the world bucket. Allocate slots in
  `createKinematicContraption` / `createBox` / `createRope` too if you want them reported.
- **Cost when enabled:** two `Instant::now()` per pair plus two relaxed `fetch_add`s; one clock pair
  per island. When disabled, one relaxed `AtomicBool::load` per sample point.
- **Units.** Per-sub-level times are nanoseconds accumulated over substeps × Rayon workers. They are
  valid for ranking sub-levels; the wall-clock totals come from `Counters`.

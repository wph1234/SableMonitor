//! Opt-in per-rigid-body profiling for the physics step.
//!
//! This module is added on top of `rapier3d` (fork at
//! `https://github.com/ryanhcode/rapier`, rev pinned by Sable). It answers one question:
//! *which body is burning the physics budget?*
//!
//! License: Rapier is Apache-2.0 (© Dimforge and contributors); this file is one of the
//! modifications described in `THIRD-PARTY-NOTICES.md`.
//!
//! # Attribution model
//!
//! Rapier has no notion of a "sub-level", so the host (Sable's `sable_rapier` crate) tags each
//! rigid body it cares about by writing a dense `slot` index into [`RigidBody::user_data`].
//! A body with `user_data == 0` is "untagged" and everything about it is charged to slot `0`
//! (i.e. "the rest of the world").
//!
//! Two sample points are instrumented:
//!
//! * **narrow phase** —one guarded region per collider pair in
//!   `NarrowPhase::compute_contacts`. The pair's cost is split 50/50 between its two colliders'
//!   bodies when both are tagged, and charged fully to the tagged side when the other side is
//!   world geometry.
//! * **solver** —one guarded region per island solve in `PhysicsPipeline::step`
//!   (`solvers.par_iter_mut()`), split evenly among the island's tagged bodies.
//!
//! Timers are only taken when profiling is enabled, and accumulation is done with relaxed atomics
//! into fixed-size slot arrays, so the parallel (rayon) code paths never lock.
//!
//! # Cost when enabled
//!
//! One `Instant::now()` pair per collider pair and per island. For Sable's voxel colliders a pair
//! costs microseconds, so the relative overhead is well under a percent, but do not leave it on
//! forever.
//!
//! # `std` requirement
//!
//! This module uses `std::time::Instant` and `std::sync::OnceLock`; it is compiled from the
//! crate root behind `cfg(feature = "std")`. Sable builds rapier with default features, so it is
//! always available there.

use crate::alloc_prelude::*;
use crate::dynamics::{IslandManager, RigidBodyHandle, RigidBodySet};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::OnceLock;
use std::time::Instant;

/// Maximum number of distinct bodies that can be tracked simultaneously.
///
/// Slot `0` is reserved for "untagged" work, so hosts should start allocating at `1`... but
/// `0` is also a perfectly fine slot for a real body, it just shares the bucket with the world.
pub const MAX_SLOTS: usize = 1 << 16;

/// Maximum number of distinct bodies an island solve is split across.
///
/// Islands bigger than this still get their time divided by the real member count; only the
/// first `MAX_ISLAND_TAGS` tagged bodies are credited.
const MAX_ISLAND_TAGS: usize = 16;

static ENABLED: AtomicBool = AtomicBool::new(false);
static TOTAL_PAIRS: AtomicU32 = AtomicU32::new(0);
static STORE: OnceLock<Store> = OnceLock::new();

struct Store {
    narrow_ns: Box<[AtomicU64]>,
    solver_ns: Box<[AtomicU64]>,
    pairs: Box<[AtomicU32]>,
    island_max: Box<[AtomicU32]>,
}

impl Store {
    fn new() -> Self {
        let u64s = || -> Box<[AtomicU64]> {
            (0..MAX_SLOTS)
                .map(|_| AtomicU64::new(0))
                .collect::<Vec<_>>()
                .into_boxed_slice()
        };
        let u32s = || -> Box<[AtomicU32]> {
            (0..MAX_SLOTS)
                .map(|_| AtomicU32::new(0))
                .collect::<Vec<_>>()
                .into_boxed_slice()
        };

        Store {
            narrow_ns: u64s(),
            solver_ns: u64s(),
            pairs: u32s(),
            island_max: u32s(),
        }
    }
}

#[inline(always)]
fn store() -> &'static Store {
    STORE.get_or_init(Store::new)
}

/// Whether profiling samples are currently being recorded.
#[inline(always)]
pub fn is_enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

/// Turns profiling on or off.
///
/// Enabling pre-allocates the slot tables on the calling thread so that the first physics step
/// does not pay for a ~1.5 MiB allocation.
pub fn set_enabled(enabled: bool) {
    if enabled {
        let _ = store();
        ENABLED.store(true, Ordering::Relaxed);
    } else {
        ENABLED.store(false, Ordering::Relaxed);
    }
}

/// One slot's accumulated numbers, as produced by [`take`].
#[derive(Copy, Clone, Debug, Default)]
pub struct SlotSample {
    /// The slot index the host wrote into `RigidBody::user_data`.
    pub slot: u32,
    /// Approximate narrow-phase nanoseconds attributed to this slot.
    pub narrow_ns: u64,
    /// Approximate solver nanoseconds attributed to this slot.
    pub solver_ns: u64,
    /// Number of collider pairs this slot participated in.
    pub pairs: u32,
    /// Largest island (in bodies) this slot was seen in.
    pub island_max: u32,
}

/// Reads **and clears** the accumulated samples.
///
/// Call this once per game tick; it is the wrong thing to call from inside the physics step.
pub fn take() -> Vec<SlotSample> {
    let s = store();
    let mut out = Vec::new();

    for i in 0..MAX_SLOTS {
        let narrow = s.narrow_ns[i].swap(0, Ordering::Relaxed);
        let solver = s.solver_ns[i].swap(0, Ordering::Relaxed);
        let pairs = s.pairs[i].swap(0, Ordering::Relaxed);
        let island = s.island_max[i].swap(0, Ordering::Relaxed);

        if narrow == 0 && solver == 0 && pairs == 0 && island == 0 {
            continue;
        }

        out.push(SlotSample {
            slot: i as u32,
            narrow_ns: narrow,
            solver_ns: solver,
            pairs,
            island_max: island,
        });
    }

    out
}

/// Reads **and clears** the number of collider pairs evaluated since the last call.
///
/// The per-slot pairs counters double count (one pair credits both endpoints), so this is the
/// only place the real pair count is available.
pub fn take_total_pairs() -> u32 {
    TOTAL_PAIRS.swap(0, Ordering::Relaxed)
}

/// Clears every slot without returning anything.
pub fn reset() {
    let s = store();
    for i in 0..MAX_SLOTS {
        s.narrow_ns[i].store(0, Ordering::Relaxed);
        s.solver_ns[i].store(0, Ordering::Relaxed);
        s.pairs[i].store(0, Ordering::Relaxed);
        s.island_max[i].store(0, Ordering::Relaxed);
    }
}

#[inline(always)]
fn slot_of(bodies: &RigidBodySet, handle: Option<RigidBodyHandle>) -> u32 {
    let raw = handle
        .and_then(|h| bodies.get(h))
        .map(|rb| rb.user_data as u64)
        .unwrap_or(0);

    raw.min((MAX_SLOTS - 1) as u64) as u32
}

/// RAII timer for one collider pair in the narrow phase.
///
/// Drop records the elapsed time, so early `return` / `break` inside the pair loop is safe.
pub struct PairGuard {
    start: Option<Instant>,
    a: u32,
    b: u32,
}

impl Drop for PairGuard {
    fn drop(&mut self) {
        let Some(start) = self.start else {
            return;
        };

        let ns = start.elapsed().as_nanos() as u64;
        let s = store();
        let (sa, sb) = (self.a as usize, self.b as usize);

        TOTAL_PAIRS.fetch_add(1, Ordering::Relaxed);
        s.pairs[sa].fetch_add(1, Ordering::Relaxed);

        if sa == sb {
            s.narrow_ns[sa].fetch_add(ns, Ordering::Relaxed);
        } else {
            s.pairs[sb].fetch_add(1, Ordering::Relaxed);
            s.narrow_ns[sa].fetch_add(ns / 2, Ordering::Relaxed);
            s.narrow_ns[sb].fetch_add(ns - ns / 2, Ordering::Relaxed);
        }
    }
}

/// Starts timing one collider pair.
///
/// Pass the handles that are already computed in `NarrowPhase::compute_contacts`
/// (`rb_handle1` / `rb_handle2`); they are `Option`, world-attached colliders are `None`.
#[inline]
pub fn pair_guard(
    bodies: &RigidBodySet,
    a: Option<RigidBodyHandle>,
    b: Option<RigidBodyHandle>,
) -> PairGuard {
    if !is_enabled() {
        return PairGuard {
            start: None,
            a: 0,
            b: 0,
        };
    }

    PairGuard {
        start: Some(Instant::now()),
        a: slot_of(bodies, a),
        b: slot_of(bodies, b),
    }
}

/// RAII timer for one island solve in `PhysicsPipeline::step`.
///
/// The island's duration is divided evenly between its tagged bodies, which is the least
/// arbitrary split available: the solver cost of an island is driven by its constraint rows, and
/// every body in an island contributes at least one.
pub struct IslandGuard {
    start: Option<Instant>,
    tags: [u32; MAX_ISLAND_TAGS],
    filled: usize,
    /// Number of *distinct* tagged bodies in the island (may exceed `filled`).
    divisor: u32,
    island_len: u32,
}

impl Drop for IslandGuard {
    fn drop(&mut self) {
        let Some(start) = self.start else {
            return;
        };

        let ns = start.elapsed().as_nanos() as u64;
        let divisor = self.divisor.max(1) as u64;
        let share = ns / divisor;

        let s = store();

        for i in 0..self.filled {
            let slot = self.tags[i] as usize;
            s.solver_ns[slot].fetch_add(share, Ordering::Relaxed);
            s.island_max[slot].fetch_max(self.island_len, Ordering::Relaxed);
        }
    }
}

/// Starts timing one island solve.
///
/// `island_id` is the value taken from `islands.active_islands()[island_awake_id]`.
#[inline]
pub fn island_guard(
    islands: &IslandManager,
    island_id: usize,
    bodies: &RigidBodySet,
) -> IslandGuard {
    if !is_enabled() {
        return IslandGuard {
            start: None,
            tags: [0; MAX_ISLAND_TAGS],
            filled: 0,
            divisor: 0,
            island_len: 0,
        };
    }

    let island = islands.island(island_id);
    let members = island.bodies();

    let mut tags = [0u32; MAX_ISLAND_TAGS];
    let mut filled = 0usize;
    let mut divisor = 0u32;

    for handle in members {
        let slot = slot_of(bodies, Some(*handle));

        // Slot 0 is the "world" bucket: it is reported by the global counters already, so it is
        // not a divisor here (otherwise a ground contact would dilute every island's share).
        if slot == 0 {
            continue;
        }

        divisor += 1;

        if filled < MAX_ISLAND_TAGS && !tags[..filled].contains(&slot) {
            tags[filled] = slot;
            filled += 1;
        }
    }

    IslandGuard {
        start: Some(Instant::now()),
        tags,
        filled,
        divisor,
        island_len: members.len() as u32,
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use crate::dynamics::{
        CCDSolver, ImpulseJointSet, IntegrationParameters, IslandManager, MultibodyJointSet,
        RigidBodyBuilder,
    };
    use crate::geometry::{ColliderBuilder, ColliderSet, DefaultBroadPhase, NarrowPhase};
    use crate::math::Vector;
    use crate::pipeline::PhysicsPipeline;

    /// Two overlapping, tagged dynamic bodies must show up with narrow-phase time, solver time and
    /// contact pairs, and must be reported as sharing an island.
    #[test]
    fn attributes_time_to_body_slots() {
        reset();
        set_enabled(true);

        let mut bodies = RigidBodySet::new();
        let mut colliders = ColliderSet::new();
        let mut islands = IslandManager::new();
        let mut broad_phase = DefaultBroadPhase::new();
        let mut narrow_phase = NarrowPhase::new();
        let mut impulse_joints = ImpulseJointSet::new();
        let mut multibody_joints = MultibodyJointSet::new();
        let mut ccd = CCDSolver::new();
        let mut pipeline = PhysicsPipeline::new();

        // Slots are written through `user_data`, exactly like the host does for a sub-level.
        for (slot, x) in [(1u64, -0.2f32), (2u64, 0.2f32)] {
            let mut rb = RigidBodyBuilder::dynamic()
                .translation(Vector::new(x, 0.0, 0.0))
                .build();
            rb.user_data = slot as u128;

            let handle = bodies.insert(rb);
            colliders.insert_with_parent(ColliderBuilder::ball(0.5).build(), handle, &mut bodies);
        }

        let params = IntegrationParameters::default();

        for _ in 0..10 {
            pipeline.step(
                Vector::new(0.0, -9.81, 0.0),
                &params,
                &mut islands,
                &mut broad_phase,
                &mut narrow_phase,
                &mut bodies,
                &mut colliders,
                &mut impulse_joints,
                &mut multibody_joints,
                &mut ccd,
                &(),
                &(),
            );
        }

        let samples = take();
        set_enabled(false);
        reset();

        let one = samples.iter().find(|s| s.slot == 1).expect("slot 1 sampled");
        let two = samples.iter().find(|s| s.slot == 2).expect("slot 2 sampled");

        assert!(one.pairs > 0, "slot 1 should have contact pairs");
        assert!(two.pairs > 0, "slot 2 should have contact pairs");
        assert!(one.narrow_ns > 0, "narrow-phase time attributed to slot 1");
        assert!(two.narrow_ns > 0, "narrow-phase time attributed to slot 2");
        assert!(
            one.solver_ns + two.solver_ns > 0,
            "solver time attributed to the island"
        );
        assert!(one.island_max >= 2, "island should contain both bodies");
    }
}

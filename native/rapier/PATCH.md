> NOTE: this file is the original design draft. The version that was actually built is
> described in `native/README.md`: `use crate::alloc_prelude::*;` is required, a
> `take_total_pairs` counter was added, and `profiler` must be enabled.

# rapier 补丁（3 个文件、共 4 处改动）

基线：`https://github.com/ryanhcode/rapier`，**使用你那份 Sable 里 `sable_rapier/Cargo.toml`
所 pin 的同一个 rev**（Sable 2.0.3 的 `main` 上目前是 `38e92f1`，但请以你的 tag 里的为准）。

```powershell
git clone https://github.com/ryanhcode/rapier C:\dev\rapier
cd C:\dev\rapier
git checkout <Sable 里 Cargo.toml 的 rev>     # 必做！否则 rapier3d 内部 API 可能对不上
```

然后把本目录下的 `src/profiling.rs` 复制到 `C:\dev\rapier\src\profiling.rs`，再改下面三处。

不需要新增任何 crate 依赖（只用 `std::time::Instant` + `std::sync::OnceLock`）。

---

## 1) `src/lib.rs` —— 注册模块

在文件顶部其他 `pub mod ...` 声明旁边加一行：

```rust
#[cfg(feature = "std")]
pub mod profiling;
```

> 这个 rev 已经支持 no-std，所以有 `std` feature。如果你编译时报
> `unresolved module or unlinked crate 'profiling'`，说明你的 rev 没有 `std` feature，
> 直接改成 `pub mod profiling;`（Sable 永远是 std 构建，不需要照顾 no-std）。

---

## 2) `src/geometry/narrow_phase.rs` —— 每个 collider 对计时

函数 `NarrowPhase::compute_contacts`（大约在第 814 行）里，并行循环的开头：

**改前**

```rust
        par_iter_mut!(&mut self.contact_graph.graph.edges).for_each(|edge| {
            let pair = &mut edge.weight;
            let had_any_active_contact = pair.has_any_active_contact();
            let co1 = &colliders[pair.collider1];
            let co2 = &colliders[pair.collider2];
            let rb_handle1 = co1.parent.map(|p| p.handle);
            let rb_handle2 = co2.parent.map(|p| p.handle);

            'emit_events: {
```

**改后**（只插入 `let _profile_pair = ...` 这一行）

```rust
        par_iter_mut!(&mut self.contact_graph.graph.edges).for_each(|edge| {
            let pair = &mut edge.weight;
            let had_any_active_contact = pair.has_any_active_contact();
            let co1 = &colliders[pair.collider1];
            let co2 = &colliders[pair.collider2];
            let rb_handle1 = co1.parent.map(|p| p.handle);
            let rb_handle2 = co2.parent.map(|p| p.handle);

            // >>> SableMonitor >>> per-pair narrow-phase timer
            let _profile_pair = crate::profiling::pair_guard(bodies, rb_handle1, rb_handle2);
            // <<< SableMonitor <<<

            'emit_events: {
```

注意：

- 必须是 `PairGuard`（Drop 里记账），不要自己写 `let t = Instant::now()` 再在闭包末尾累加 ——
  这个闭包里有 `return` 和 `break 'emit_events`，末尾记账会漏掉这些分支。
- `bodies` 在此函数里是 `&mut RigidBodySet`，传给 `&RigidBodySet` 参数会自动重借用，不需要 `&*`。
- **不要**在同文件的 `compute_intersections`（约 831 行那个 `intersection_graph` 的循环）里加，
  sensor 的相交检测和 sub-level 负载无关。

---

## 3) `src/pipeline/physics_pipeline.rs` —— 每个 island 求解计时

在 `build_islands_and_solve_velocity_constraints` 的并行求解分支里
（`#[cfg(feature = "parallel")]` 内，`solvers.par_iter_mut()`），闭包内部：

**改前**

```rust
                    .for_each(|(island_awake_id, solver)| {
                        let island_id = islands.active_islands()[island_awake_id];
                        let bodies: &mut RigidBodySet =
                            unsafe { &mut *bodies.load(Ordering::Relaxed) };
                        let manifolds: &mut Vec<&mut ContactManifold> =
                            unsafe { &mut *manifolds.load(Ordering::Relaxed) };
                        let impulse_joints: &mut Vec<JointGraphEdge> =
                            unsafe { &mut *impulse_joints.load(Ordering::Relaxed) };
                        let multibody_joints: &mut MultibodyJointSet =
                            unsafe { &mut *multibody_joints.load(Ordering::Relaxed) };

                        let mut counters = Counters::new(false);
                        solver.init_and_solve(
```

**改后**（在 `let mut counters` 之前插入一行）

```rust
                    .for_each(|(island_awake_id, solver)| {
                        let island_id = islands.active_islands()[island_awake_id];
                        let bodies: &mut RigidBodySet =
                            unsafe { &mut *bodies.load(Ordering::Relaxed) };
                        let manifolds: &mut Vec<&mut ContactManifold> =
                            unsafe { &mut *manifolds.load(Ordering::Relaxed) };
                        let impulse_joints: &mut Vec<JointGraphEdge> =
                            unsafe { &mut *impulse_joints.load(Ordering::Relaxed) };
                        let multibody_joints: &mut MultibodyJointSet =
                            unsafe { &mut *multibody_joints.load(Ordering::Relaxed) };

                        // >>> SableMonitor >>> per-island solver timer
                        let _profile_island =
                            crate::profiling::island_guard(islands, island_id, &*bodies);
                        // <<< SableMonitor <<<

                        let mut counters = Counters::new(false);
                        solver.init_and_solve(
```

要点：

- `island_guard` 在构造时就把 tag 快照下来了，**不持有** `bodies` 的借用，所以下面
  `solver.init_and_solve(..., bodies, ...)` 的 `&mut` 不会冲突。
- 顺序很重要：必须在 `let bodies = ...` 之后。串行分支（`#[cfg(not(feature = "parallel"))]`）
  也要加同样一行（`island_awake_id` / `island_id` 命名相同，位置在 `self.solvers[..].init_and_solve` 之前）。
- 如果你只想量并行分支，Sable 用的是 `features = ["parallel"]`，串行分支不会被编译。

---

## 验证补丁能编译

```powershell
cd C:\dev\rapier\crates\rapier3d
cargo check --features "simd-stable,dim3,f32"
```

（`simd-nightly` 需要 nightly 工具链，先在这里用 `simd-stable` 做快速语法/类型检查，能过即可。）

如果报 `island.bodies()` 不可访问，说明这个 rev 的 `Island` 没有暴露 `bodies()`，
改用 `crate::profiling::island_guard` 里的 `members` 换成 `islands.active_bodies()` 就不对了 ——
正确做法是给 `Island` 加一行 `pub fn bodies(&self) -> &[RigidBodyHandle] { &self.bodies }`
（`src/dynamics/island_manager/island.rs`，`38e92f1` 上已经有了）。

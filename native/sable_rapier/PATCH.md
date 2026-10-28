> NOTE: this file is the original design draft. The version that was actually built is
> described in `native/README.md`: the Java package is `natives`, `rapier3d` needs the
> `profiler` feature, and a `take_total_pairs` helper was added.

# sable_rapier 补丁（把 rapier 的采样变成 Java 能读的数字）

对象：Sable 源码里 `sable_rapier/src/main/rust/rapier/` 这个 crate。

```powershell
git clone --branch mc1.21.1-2.0.6-neoforge --depth 1 https://github.com/ryanhcode/sable C:\dev\sable
```

> 用和你 `gradle.properties` 里 `sable_version=2.0.6` 对应的 tag，保证 Java 侧
> `Rapier3D` 的 native 方法签名和 DLL 完全一致。

改动共 4 处，都在 `sable_rapier/src/main/rust/rapier/` 下。

---

## 1) `Cargo.toml` —— 指向你打过补丁的 rapier

把

```toml
rapier3d = { git = "https://github.com/ryanhcode/rapier", rev = "38e92f117590862481a53df6fc69a5d893e29186", features = [
    "simd-nightly",
    "parallel",
] }
```

改成（路径按你本地 clone 位置）：

```toml
rapier3d = { path = "C:/dev/rapier/crates/rapier3d", features = ["simd-nightly", "parallel"] }
```

`crates/rapier3d/Cargo.toml` 的 `package.name` 必须仍然是 `rapier3d`（这个 rev 就是），否则
`use rapier3d::...` 全部失效。

---

## 2) `src/scene.rs` —— 每个 sub-level 一个 slot

在 `SimulationSceneData` 后面，`SableSceneData` 里加三个字段：

```rust
pub struct SableSceneData {
    // ... 原有字段保持不变 ...

    /// profiling slot -> sable sub-level id（`-1` 表示该 slot 空闲）
    pub profile_slots: Vec<i32>,
    /// 被释放、可以复用的 slot
    pub profile_free_slots: Vec<u32>,
    /// sable sub-level id -> profiling slot
    pub profile_slot_of: HashMap<LevelColliderID, u32>,
}
```

在 `impl SableSceneData` 里加：

```rust
impl SableSceneData {
    /// 为一个 sub-level 分配（或复用）profiling slot
    pub fn alloc_profile_slot(&mut self, id: LevelColliderID) -> u32 {
        if let Some(slot) = self.profile_slot_of.get(&id) {
            return *slot;
        }

        let slot = if let Some(slot) = self.profile_free_slots.pop() {
            self.profile_slots[slot as usize] = id as i32;
            slot
        } else {
            let slot = self.profile_slots.len() as u32;
            if slot as usize >= rapier3d::profiling::MAX_SLOTS {
                // 超出上限就退化成 slot 0（"世界"桶），不会崩
                return 0;
            }
            self.profile_slots.push(id as i32);
            slot
        };

        self.profile_slot_of.insert(id, slot);
        slot
    }

    pub fn free_profile_slot(&mut self, id: LevelColliderID) {
        if let Some(slot) = self.profile_slot_of.remove(&id) {
            if let Some(entry) = self.profile_slots.get_mut(slot as usize) {
                *entry = -1;
            }
            self.profile_free_slots.push(slot);
        }
    }
}
```

同时给 `PhysicsScene` 加一个全局阶段耗时累加器（`step` 里是 `&PhysicsScene`，所以用 `Mutex`）：

```rust
#[derive(Default, Clone, Copy)]
pub struct GlobalAccum {
    pub step_ms: f64,
    pub broad_ms: f64,
    pub narrow_ms: f64,
    pub island_ms: f64,
    pub solver_ms: f64,
    pub ccd_ms: f64,
    pub update_ms: f64,
    pub contact_pairs: f64,
}

impl GlobalAccum {
    pub fn add(&mut self, c: &rapier3d::counters::Counters) {
        self.step_ms += c.step_time_ms();
        self.broad_ms += c.cd.broad_phase_time.time_ms();
        self.narrow_ms += c.cd.narrow_phase_time.time_ms();
        self.island_ms += c.stages.island_construction_time.time_ms();
        self.solver_ms += c.stages.solver_time.time_ms();
        self.ccd_ms += c.stages.ccd_time.time_ms();
        self.update_ms += c.stages.update_time.time_ms();
        self.contact_pairs += c.cd.ncontact_pairs as f64;
    }
}

pub struct PhysicsScene {
    // ... 原有字段保持不变 ...

    /// 一个 tick 内所有 substep 的全局阶段耗时累加
    pub global_accum: std::sync::Mutex<GlobalAccum>,
}
```

---

## 3) `src/lib.rs` —— 打 tag、累加计数器

### 3.1 `initialize` 里初始化新字段

```rust
    let sable_data = Arc::new(RwLock::new(SableSceneData {
        main_level_chunks: HashMap::<i64, ChunkSection>::new(),
        octree_chunks: HashMap::<i64, OctreeChunkSection>::new(),
        joint_set: SableJointSet::new(),
        rope_map: RopeMap::default(),
        level_colliders: HashMap::<LevelColliderID, ActiveLevelColliderInfo>::new(),
        rigid_bodies: HashMap::<LevelColliderID, RigidBodyHandle>::new(),
        // 新增
        profile_slots: Vec::new(),
        profile_free_slots: Vec::new(),
        profile_slot_of: HashMap::new(),
    }));
```

```rust
    let mut scene = PhysicsScene {
        sim_data: RwLock::new(SimulationSceneData { /* ... 不变 ... */ }),
        sable_data,
        ground_handle: None,
        reported_collisions,
        current_step_vm,
        gravity: Vec3::new(x as Real, y as Real, z as Real),
        universal_drag: universal_drag as Real,
        manifold_info_map,
        // 新增
        global_accum: std::sync::Mutex::new(GlobalAccum::default()),
    };
```

### 3.2 `createSubLevel` 里写 `user_data`

**改前**

```rust
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap();

        let handle = sim_data.rigid_body_set.insert(rigid_body);
```

**改后**

```rust
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap();

        // >>> SableMonitor >>> profiling slot 作为 user_data 交给 rapier
        let profile_slot = sable_data.alloc_profile_slot(id as LevelColliderID);
        rigid_body.user_data = profile_slot as u128;
        // <<< SableMonitor <<<

        let handle = sim_data.rigid_body_set.insert(rigid_body);
```

> `RigidBody::user_data` 是 `pub` 字段（bevy_rapier 也这么读）。如果你的 rev 把它藏成私有，
> 改成在 `RigidBodyBuilder::dynamic()` 后面接 `.user_data(profile_slot as u128)`；
> 但那样要先分配 slot，把 `alloc_profile_slot` 提到 `RigidBodyBuilder` 之前。

### 3.3 `removeSubLevel` 里回收 slot

```rust
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap();

        // >>> SableMonitor >>>
        sable_data.free_profile_slot(id as LevelColliderID);
        // <<< SableMonitor <<<

        sable_data.level_colliders.remove(&(id as LevelColliderID));
```

### 3.4 `step` 里累加全局阶段耗时

**改前**

```rust
            sim.pipeline.step(
                gravity,
                params,
                // ...
            );
        });
    }));
```

**改后**（在 `step` 调用之后、闭包结束之前）

```rust
            sim.pipeline.step(
                gravity,
                params,
                // ...
            );

            // >>> SableMonitor >>> 累加本 substep 的阶段耗时
            scene
                .global_accum
                .lock()
                .unwrap()
                .add(&sim.pipeline.counters);
            // <<< SableMonitor <<<
        });
    }));
```

> `PhysicsPipeline::new()` 里是 `Counters::new(true)`，`step()` 开头会 `reset()`，
> 所以读到的就是"刚才这一个 substep"的数据。一个游戏 tick 有多个 substep，
> 靠 `GlobalAccum` 累加成一个 tick 的总量。

### 3.5 新增两个 JNI 导出

JNI 符号名必须和 Java 类的全限定名一一对应：
`dev.wphuujmhu.sablemonitor.natives.NativePhysicsProfile`。

```rust
/// 打开 / 关闭 per-sub-level 采样
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_setEnabled0<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    enabled: jboolean,
) {
    rapier3d::profiling::set_enabled(enabled != 0);
}

/// 读出并清空本 tick 的采样。
///
/// 返回格式（全部为 double）：
///   [0] step_ms   [1] broad_ms  [2] narrow_ms  [3] island_ms
///   [4] solver_ms [5] ccd_ms    [6] update_ms  [7] contact_pairs
///   [8] recordCount
///  然后 recordCount 组、每组 5 个：
///   id, narrow_ns, solver_ns, pairs, island_max
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_takeProfile0<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JDoubleArray<'local> {
    let mut out: Vec<jdouble> = Vec::with_capacity(64);

    with_handle(handle, |scene| {
        // 全局部分
        {
            let mut accum = scene.global_accum.lock().unwrap();
            out.push(accum.step_ms);
            out.push(accum.broad_ms);
            out.push(accum.narrow_ms);
            out.push(accum.island_ms);
            out.push(accum.solver_ms);
            out.push(accum.ccd_ms);
            out.push(accum.update_ms);
            out.push(accum.contact_pairs);
            *accum = GlobalAccum::default();
        }

        // per-sub-level 部分
        let sable_data = scene.sable_data.read().unwrap();
        let samples = rapier3d::profiling::take();

        let mut records: Vec<jdouble> = Vec::with_capacity(samples.len() * 5);
        let mut count = 0.0;

        for sample in samples {
            if sample.slot == 0 {
                // slot 0 = 未打 tag 的世界几何 / 地面，不计入 sub-level 报表
                continue;
            }

            let Some(&id) = sable_data.profile_slots.get(sample.slot as usize) else {
                continue;
            };

            if id < 0 {
                continue;
            }

            records.push(id as jdouble);
            records.push(sample.narrow_ns as jdouble);
            records.push(sample.solver_ns as jdouble);
            records.push(sample.pairs as jdouble);
            records.push(sample.island_max as jdouble);
            count += 1.0;
        }

        out.push(count);
        out.extend_from_slice(&records);
    });

    let array = env.new_double_array(out.len() as jint).unwrap();
    env.set_double_array_region(&array, 0, &out).unwrap();
    array
}
```

`lib.rs` 顶部记得加 `use crate::scene::GlobalAccum;`（或写全路径 `scene::GlobalAccum`）。

---

## 编译

见 `native/BUILD.md`。

## 注意

- **slot 只覆盖 dynamic sub-level**。kinematic contraption / box / rope 目前都是 `user_data == 0`，
  会被算进"世界"桶（`slot 0`），而 `takeProfile0` 会把 slot 0 过滤掉。想要它们的话，
  在 `createKinematicContraption` / `createBox` / `createRope` 里同样分配 slot 即可。
- 关闭 profiling 时（默认）热路径上只有一个 `AtomicBool::load`，开销可忽略。
- `profiling::take()` 是 `O(65536)` 的 `swap` 扫描，每 tick 一次，约几十微秒，可以接受；
  如果在意，把 `MAX_SLOTS` 降到 `1 << 13`。

# 构建打过补丁的 `sable_rapier` 原生库（Windows x86_64）

目标：产出 `sable_rapier_x86_64_windows.dll`，让 Sable 加载的是它，而不是 Sable jar 里自带的那个。

需要的目录结构（路径随意，下面按这个写）：

```
C:\dev\rapier                     <- ryanhcode/rapier，打了 native/rapier/PATCH.md 的补丁
C:\dev\sable                      <- ryanhcode/sable，tag mc1.21.1-2.0.5-neoforge
C:\Users\wangl\IdeaProjects\SableMonitor
```

---

## 1. 装 Rust 工具链

```powershell
winget install --id Rustlang.Rustup -e
```

装完**重开一个终端**，确认：

```powershell
rustup --version
cargo --version
```

如果 `cargo` 还不在 PATH，把 `%USERPROFILE%\.cargo\bin` 加进 PATH，或直接
`& "$env:USERPROFILE\.cargo\bin\cargo.exe" --version`。

> Sable 的 Rust workspace 带 `rust-toolchain.toml`，里面 pin 了 nightly 通道。
> 只要 `rustup` 装好，进入该目录执行 `cargo` 时 rustup 会自动下载对应 nightly，
> 不需要手动 `rustup default nightly`。Sable 用 `simd-nightly` feature，**必须**是 nightly。

### MSVC 链接器

Rust 在 Windows 上默认用 `x86_64-pc-windows-msvc`，需要 MSVC：

1. 下载 [Visual Studio Build Tools 2022](https://visualstudio.microsoft.com/downloads/)。
2. 勾选 **使用 C++ 的桌面开发**（MSVC v143 + Windows 10/11 SDK）。
3. 装完重开终端，验证：

```powershell
rustup show            # 应该看到 x86_64-pc-windows-msvc (default)
```

不需要装完整 Visual Studio，Build Tools 就够。

---

## 2. 拉源码

```powershell
git clone https://github.com/ryanhcode/rapier C:\dev\rapier
cd C:\dev\rapier
# 用 sable 里 Cargo.toml pin 的同一个 rev
git checkout <rev>

git clone --branch mc1.21.1-2.0.5-neoforge --depth 1 https://github.com/ryanhcode/sable C:\dev\sable
```

然后按顺序打补丁：

1. `native/rapier/PATCH.md` → 改 `C:\dev\rapier`（4 处，含新增 `src/profiling.rs`）
2. `native/sable_rapier/PATCH.md` → 改 `C:\dev\sable\sable_rapier\src\main\rust\rapier\`（4 处）

偷懒也可以直接套用 `native/patches/` 里导出的 diff（就是上面两步做完的结果）：

```powershell
git -C C:\dev\rapier apply C:\Users\wangl\IdeaProjects\SablelMonitor\native\patches\rapier-profiling.patch
git -C C:\dev\sable  apply C:\Users\wangl\IdeaProjects\SablelMonitor\native\patches\sable-rapier-profiling.patch
```

当前实际验证过的状态、2.0.5 与 2.0.3 的差异，都记在 `native/README.md`。

---

## 3. 编译

```powershell
cd C:\dev\sable\sable_rapier\src\main\rust
cargo build --release
```

产物：

```
C:\dev\sable\sable_rapier\src\main\rust\target\release\sable_rapier.dll
```

第一次编译要下依赖 + 编 rapier/parry/nalgebra，大概 5~10 分钟。

### 先验证语法（可选，但强烈建议）

在编整个 sable 之前，先单独 check 一下 rapier 补丁：

```powershell
cd C:\dev\rapier\crates\rapier3d
cargo check --features "simd-stable,dim3,f32"
```

能过就说明 `profiling.rs` 和两处插入点没问题（`island.bodies()`、`user_data` 这些私有/公有访问都成立）。
rapier 的 `parallel` feature 在 `cargo check` 里也可以加：`--features "simd-stable,dim3,f32,parallel"`。

---

## 4. 导出符号自检（关键一步）

```powershell
dumpbin /exports C:\dev\sable\sable_rapier\src\main\rust\target\release\sable_rapier.dll | Select-String "SableMonitor"
```

必须能看到这两个（名字由 JNI 命名规则从 Java 类全名生成，大小写和 `_` 都不能错）：

```
Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_setEnabled0
Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_takeProfile0
```

看不到 = `#[unsafe(no_mangle)]` 拼错、类名/包名不一致，或者你改的不是被编译的那个 crate。

同时确认原有的 Sable 符号还在（说明你编的是同一个 crate，不是新 crate）：

```powershell
dumpbin /exports ...\sable_rapier.dll | Select-String "Rapier3D_step"
```

---

## 5. 放进 mod 资源目录

```powershell
$dst = "C:\Users\wangl\IdeaProjects\SableMonitor\src\main\resources\natives\sable_rapier"
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item C:\dev\sable\sable_rapier\src\main\rust\target\release\sable_rapier.dll `
          "$dst\sable_rapier_x86_64_windows.dll" -Force
```

`PatchedNatives.fileName()` 会按平台拼出这个名字（Windows: `sable_rapier_x86_64_windows.dll`），
和 Sable 自己的命名一致。

然后：

```powershell
cd C:\Users\wangl\IdeaProjects\SableMonitor
.\gradlew runClient
```

（DLL 已在 `src/main/resources` 下，`processResources` 会自动打进 jar，不需要额外 Gradle 配置。
如果 Gradle 缓存了旧 jar，`.\gradlew runClient --rerun-tasks` 或先 `.\gradlew clean`。）

---

## 6. 游戏内验证

```
/sablemonitor profile on
# 放一个 sub-level，撞点东西
/sablemonitor dump
```

预期输出类似：

```
Physics 3.417 ms  (broad 0.081 | narrow 2.902 | island 0.013 | solve 0.391 | ccd 0.000)  pairs 1284
id     narrow   solver    total  pairs island
17      2.631    0.312    2.943    903      2
```

- 第一行是**全局**阶段耗时（一个 tick 内所有 substep 累加），和 `pipeline.counters` 一致。
- 下面每行是一个 sub-level：`narrow` = 它参与的 collider 对的接触生成时间（两侧各分一半），
  `solver` = 所在 island 求解时间按 island 内被打 tag 的 body 数均摊，`pairs` 是接触对数，
  `island` 是该 island 的 body 数量（>2 说明它和别的 sub-level / 机械结构连在一起）。

---

## 常见问题

**`/sablemonitor dump` 说 "Patched rapier natives are not loaded"**
mixin 没生效或 DLL 没进 jar。检查日志里有没有
`[SableMonitor] loaded patched rapier natives from ...`。

**`UnsatisfiedLinkError: ... NativePhysicsProfile_setEnabled0`**
DLL 被加载了但没有这两个符号 → 回到第 4 步。

**编译报 `cannot find module profiling` / 找不到 `profiling::pair_guard`**
`src/lib.rs` 的 `pub mod profiling;` 没加，或加在了 `cfg(not(feature = "std"))` 分支里。

**编译报 `island.bodies()` 是 private**
这个 rev 的 `Island` 没暴露 `bodies()`。在
`crates/rapier3d/src/dynamics/island_manager/island.rs`（或 `src/dynamics/...`）里加：

```rust
pub fn bodies(&self) -> &[RigidBodyHandle] { &self.bodies }
```

**不想用 nightly**
把 `sable_rapier/Cargo.toml` 里的 `"simd-nightly"` 改成 `"simd-stable"`，就能用 stable 编。
功能等价，只是 SIMD 代码生成路径不同，会让绝对耗时略有变化 —— 做**对比**时不要一半 nightly 一半 stable。

**只想先看个大概**
即使是原版 Sable natives，第一阶段也可以只暴露全局 `pipeline.counters`（不需要改 rapier），
但那只能告诉你"narrow phase 很贵"，说不出是谁贵 —— 这就是必须打 rapier 补丁的原因。

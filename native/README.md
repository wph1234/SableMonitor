# native/ —— 已构建完成的 patched rapier

这里记录 **实际做出来、能编译、能在游戏里加载** 的那一版，而不是最初的设想稿。

当前状态（Sable **2.0.6** + 重命名后的工程）：

- Java 包 `dev.wphuujmhu.sablemonitor`，mod id `sablemonitor`，版本 `0.5.1-alpha-sable2.0.6`
- 原生库按 Sable **2.0.6** 的 Rust 源码构建（`.native-src/sable` 在分支 `patch-2.0.6`）
- JNI 导出用新包名（`..._sablemonitor_natives_...`）
- 已在干净的 dedicated server（`run/server` 之外的单开目录）上验证：natives 加载、`probe` 日志有非 0 数据

```
src/main/resources/natives/sable_rapier/sable_rapier_x86_64_windows.dll   1,700,352 B (1.62 MiB)
  sha256 2edb358a69de975abb901b8da0636047d9dd88bac38ab7d7516fb99ca22c3d25
  -> 由 .native-src/sable/sable_rapier/src/main/rust/target/release/sable_rapier.dll 拷贝而来
```

> **这个 DLL 不进 git**（`src/main/resources/natives/` 已加进 `.gitignore`），只作为 GitHub Release
> 附件发布；仓库里只留源码 + 补丁。克隆下来构建时如果没放 DLL，jar 仍能编译，但
> `/sablemonitor` 会报 "natives are not loaded"（`processResources` 会给警告）。
>
> **许可**：DLL 是 Sable（PolyForm Shield 1.0.0）与 Rapier（Apache-2.0）的**修改版构建**，
> 分发时必须随附 `licenses/` 下的许可原文与 [`../THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)；
> `gradlew build` 已经把这些文件打进 jar 的 `META-INF/`。本仓库自己的代码是 MIT（见 `../LICENSE`）。

> **2.0.5 与 2.0.6 的原生库完全一致**，所以上面这个 DLL 同时适用两个版本：
> jar 里 `natives/sable_rapier/sable_rapier_binaries.zip.l4z` 的 SHA-256 在两个 tag 上都是
> `986d1edc…`；`sable_rapier/src/main/rust` 两个 tag 之间 `git diff` 为 0 行；
> 2.0.6 的 Java 侧只改了一个不相关的文件（`TheFasterEntityCollisionContext`）。
> 只有 Sable 升级到动了 native 的版本时，才需要按 §4 重编。

---

## 1. 改了哪些文件（以 `.native-src/` 下的实际代码为准）

本地工作副本（已加进 `.gitignore`）：

| 路径 | 说明 |
|---|---|
| `.native-src/rapier` | `ryanhcode/rapier` @ `38e92f1`（= Sable 2.0.5 / 2.0.6 的 `Cargo.toml` 里 pin 的同一个 rev） |
| `.native-src/sable` | `ryanhcode/sable` @ tag `mc1.21.1-2.0.6-neoforge`，本地分支 `patch-2.0.6` |
| `.cargo-home` | 本项目内的 `CARGO_HOME`，避免写 `~/.cargo` |

### rapier（4 处）

| 文件 | 改动 |
|---|---|
| `src/profiling.rs` | **新增**。slot 表、`PairGuard`、`IslandGuard`、`take()`、`take_filtered()`、`take_total_pairs()`，外加一个单元测试 |
| `src/lib.rs` | `pub mod profiling;` |
| `src/geometry/narrow_phase.rs` | `compute_contacts` 的 `par_iter_mut` 循环里插入 `let _profile_pair = ...` |
| `src/pipeline/physics_pipeline.rs` | 并行 island 求解闭包里插入 `let _profile_island = ...` |

### sable_rapier（4 处）

| 文件 | 改动 |
|---|---|
| `rapier/Cargo.toml` | `rapier3d` 由 git 依赖改为**相对路径**依赖；features = `simd-nightly` + `parallel` + **`profiler`** |
| `rapier/src/scene.rs` | `SableSceneData` 加 profiling slot 字段 + `alloc/free_profile_slot`；`PhysicsScene` 加 `profile` 状态；`GlobalAccum` / `ProfileTick` / `ProfileState` |
| `rapier/src/lib.rs` | slot 池（`alloc_slot`/`free_slot`）；`createSubLevel` 分配 slot 写入 `rigid_body.user_data`，`removeSubLevel` 回收；`tick()` 结算一个 tick 的窗口；`step()` 累加阶段耗时；文件末尾两个 JNI 导出 |
| `.cargo/config.toml` | 追加 `[source.crates-io] replace-with = "vendored-sources"`（离线构建用） |

> 官方 Rust 改动史：2.0.3 → 2.0.5 只有一处（`lib.rs` 加 `LogWriter` 吞掉 stdout 的
> `BrokenPipe`，并把 `fern` 输出改成 `.chain(fern::Output::writer(...))`，已合并进我们打过补丁的
> `lib.rs`）；**2.0.5 → 2.0.6 的 `sable_rapier/src/main/rust` 没有任何改动**。
> `scene.rs`、`rust-toolchain.toml`、rapier 的 rev 在三个 tag 之间都没变。

### Java 侧同步改动

| 文件 | 改动 |
|---|---|
| `NativePhysicsProfile` / Rust 导出名 | 包名从 `sublevelmonitor` 改为 `sablemonitor`，JNI 符号随之改变（见 §2.2） |
| `PatchedNatives` | 解压目录镜像 Sable 2.0.5+ 的 `SableLoaderPlatform.getGameDirectory()`（拿不到就退回 `.sable/natives`）；文件名仍是内容寻址（`-<8 hex>`） |
| `ProfilingTicker` | 系统属性改为 `-Dsablemonitor.profile[.log]` |
| `RapierNativeLoaderMixin` | 注入方法改名为 `sablemonitor$usePatchedNatives` |

---

## 2. 三个必须知道的坑（都是实测踩出来的）

### 2.1 rapier 的 `Counters` 默认全是 0

`rapier3d` 的 `Timer` 在 **没有 `profiler` feature** 时 `start()/pause()` 完全是空操作。
所以最初那版「全局阶段耗时」一直是 `0.000ms`。**必须给 `rapier3d` 开 `profiler` feature**
（它只多一个 `web-time` 依赖，原生平台就是 `std::time::Instant` 的薄封装）。
`web-time` 不在 Sable 的 `Cargo.lock` 里，需要手动 vendor（见 §4）。

### 2.2 JNI 符号名 = Java 类的全限定名，`natives` 是复数

Java 包是 `dev.wphuujmhu.sablemonitor.natives`（**`natives`**，不是 `native` ——
`native` 是 Java 关键字，不能做包名）。因此符号必须是：

```
Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_setEnabled0
Java_dev_wphuujmhu_sablemonitor_natives_NativePhysicsProfile_takeProfile0
```

**改包名 / 改类名 = 必须重编 DLL**，否则 `/sablemonitor` 会报
`Patched rapier natives are not loaded`，日志里是
`UnsatisfiedLinkError: ...NativePhysicsProfile.setEnabled0(boolean)`。

### 2.3 PowerShell 5.1 的 `Set-Content` 会把 UTF-8 写成 GBK

这台机器上 `pwsh` 实际是 PowerShell **5.1**，`Set-Content` 默认按 ANSI(GBK) 写盘，
把 `profiling.rs` 里的 `—` 之类的字符写坏，rustc 报 `stream did not contain valid UTF-8`。
改 Rust 源码请用 `edit`/`write` 工具，或用 Python 写文件。

---

## 3. 验证结果（Sable 2.0.6 上重跑）

| 验证项 | 方法 | 结果 |
|---|---|---|
| rapier 补丁 + sable 补丁能编译 | `cargo build --release`（sable workspace，nightly） | ✅ 2.0.6 源码树无改动，`Finished release profile` |
| 2.0.6 原生库与 2.0.5 相同 | 比对 jar 内 `sable_rapier_binaries.zip.l4z` 的 SHA-256 | ✅ 两版都是 `986d1edc…` |
| DLL 导出符号 | `dumpbin /exports` | ✅ 55 个导出，含两个 `sablemonitor` 符号 |
| Gradle 打包 | `gradlew build --offline` | ✅ `sablemonitor-0.5.0-alpha-sable2.0.6.jar` 793,500 B，含 DLL |
| jar 内 DLL 与构建产物一致 | SHA-256 对比 | ✅ `2edb358a…` 一致 |
| 依赖范围生效 | jar 内 `neoforge.mods.toml` | ✅ `sable` 依赖 `versionRange = "[2.0.6]"`，服务端正常加载 |
| Sable 2.0.6 加载我们的 DLL | 干净 dedicated server | ✅ `loaded patched rapier natives from ...\sable_rapier_x86_64_windows-2edb358a.dll` |
| 采集有非 0 数据 | 同上 + `-Dsablemonitor.profile=true` | ✅ 每 20 tick：`step≈0.07ms/tick substeps=2 broad=0.003 collision=0.008 islands=0.000 physics=0.052` |
| 服务端正常启动 | 同上 | ✅ `Done (3.414s)!`（Mod List: `Sable 2.0.6` / `Sable Monitor 0.5.0-alpha-sable2.0.6`） |

> 这个空世界出生点附近没有 sub-level，所以 `pairs=0`、没有 per-sub-level 行。
> per-id 行的最后确认仍需要在有 sub-level 的世界里做（见 §5）。

---

## 4. 重建流程（我实际用的命令）

```powershell
$ws = "C:\Users\wangl\IdeaProjects\SablelMonitor"
$env:CARGO_HOME  = "$ws\.cargo-home"
$env:CARGO_NET_OFFLINE = "true"

# .native-src/sable 在分支 patch-2.0.6（= tag mc1.21.1-2.0.6-neoforge + 我们的补丁）
# 依赖已 vendored 到 .native-src/sable/sable_rapier/src/main/rust/vendor（170 个 crate）
cd "$ws\.native-src\sable\sable_rapier\src\main\rust"
cargo build --release --lib

Copy-Item target\release\sable_rapier.dll `
          "$ws\src\main\resources\natives\sable_rapier\sable_rapier_x86_64_windows.dll" -Force

# 确认导出
& "D:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.50.35717\bin\Hostx64\x64\dumpbin.exe" `
  /exports "$ws\src\main\resources\natives\sable_rapier\sable_rapier_x86_64_windows.dll" |
  Select-String "sablemonitor"

# 打包
cd $ws
.\gradlew.bat build --offline

# 发版时把 DLL 单独作为 release 附件（它不在 git 里）
#   upload: src\main\resources\natives\sable_rapier\sable_rapier_x86_64_windows.dll
# 依赖/许可清单有变动时刷新 crate 表：
#   python native\tools\notices.py
```

临时换个依赖的最小验证服（`run-verify/` 里没有任何第三方 mod jar）。build.gradle 里默认没有这个
run config，需要临时加一段（验证完可以删掉）：

```groovy
verifyServer {
    server()
    programArgument '--nogui'
    gameDirectory = project.file('run-verify')
}
```

```powershell
$env:JAVA_TOOL_OPTIONS = "-Dsablemonitor.profile=true -Dsablemonitor.profile.log=true"
.\gradlew.bat runVerifyServer --offline
```

`native/tools/vendor.py` 是补依赖用的（这台机器 schannel TLS 被拦，`cargo`/`curl`/`git`
用 schannel 都会 `SEC_E_NO_CREDENTIALS`，只有 Python 的 OpenSSL 能出网）：

```powershell
python native\tools\vendor.py `
  "$ws\.native-src\sable\sable_rapier\src\main\rust\Cargo.lock" `
  "$ws\.native-src\sable\sable_rapier\src\main\rust\vendor" 8
```

它按 `Cargo.lock` 里的 `checksum` 校验每个 `.crate`，再解包并写 `.cargo-checksum.json`
（和 `cargo vendor` 的目录源格式一致）。网络正常时用 `cargo vendor` 效果相同。

依赖集合变化后刷新许可清单里的 crate 表：

```powershell
python native\tools\notices.py
```

git 也要用 openssl 后端：

```powershell
git -c http.sslBackend=openssl clone ...
```

### 工具链

与 Sable 官方构建一致：

- `rust-toolchain.toml` = `nightly-2026-01-29`（Sable 仓库里 pin 的那个版本）
- features = `simd-nightly` + `parallel` + `profiler`

安装 nightly 时遇到过的坑（记录一下，换机器复现时用得上）：

- `static.rust-lang.org` 一开始几乎不可达（TCP 超时 / TLS handshake EOF），
  中断后 `~/.rustup/toolchains/nightly-2026-01-29-*` 会留下一个 **没有 manifest 的残缺目录**，
  后续 `rustc --version` 会报 `missing manifest in toolchain`。修法：
  `rustup toolchain uninstall nightly-2026-01-29`，删掉残留目录，再装。
- 阿里云镜像的 manifest 是裁剪过的，会报 `server sent a broken manifest: ... loongarch64-...`。
- 网络恢复后官方源 1.4 MB/s，直接装即可。

---

## 5. 还需要你做的：游戏内确认 per-sub-level 数据

headless 环境没法让玩家站到一个 sub-level 旁边，所以「真实 sub-level 的 per-id 数据」
还差最后一步人工确认。

**客户端**（推荐）：

```
gradlew runClient
# 进一个有 sub-level 的世界，站到它旁边
/sablemonitor profile start
/sablemonitor dump
```

**服务端 / 无头**（不用敲命令，直接打日志）：

```
$env:JAVA_TOOL_OPTIONS = "-Dsablemonitor.profile=true -Dsablemonitor.profile.log=true"
gradlew runVerifyServer --offline
```

`ProfilingTicker` 会每 20 tick 打一行：

```
SableMonitor: probe t=1580 step=0.164ms/tick substeps=2 broad=0.003 collision=0.009 islands=0.001 physics=0.147
  | id=17 collision~=0.031 physics~=0.284 pairs=9 island=2
```

> **列名对照**（`natives` 里的字段名保持 rapier 的原始叫法，只有显示和日志用通俗词）：
>
> | 显示 / 日志 | 代码（`NativePhysicsProfile`） | rapier 阶段 |
> |---|---|---|
> | `collision` | `narrowMs()` | narrow phase，生成接触点 |
> | `physics` | `solverMs()` | solver，解约束 / 积分 |
> | `step` | `stepMs()` | `PhysicsPipeline::step` 整段 |
> | `broad` / `islands` / `ccd` | `broadMs()` / `islandMs()` / `ccdMs()` | 宽相 / island 构建 / CCD |

---

## 6. 其余文件

- `native/rapier/PATCH.md`、`native/sable_rapier/PATCH.md` —— 补丁说明（逐步操作版）。
  和当前实际代码的差异：`profiler` feature、`alloc_prelude` 导入、`take_filtered`、
  包名 `natives`、2.0.5 的 `LogWriter`。以 `.native-src/` 里的代码和一节 §1 为准。
- `native/BUILD.md` —— 从零开始的工具链安装说明（rustup / MSVC），仍然有效。
- `native/tools/vendor.py` —— 离线 vendor 脚本。
- `.native-src/sable/sable_rapier/src/main/rust/rapier/src/bin/probe.rs` —— 独立验证程序，
  `cargo run --release --bin probe` 即可跑，不依赖 Minecraft。

---

## 7. 补丁存档（`.native-src/` 丢了也能重建）

`.native-src/` 是 gitignore 的，所以补丁的实际 diff 另外存了一份：

```powershell
# 重新 clone 之后
git clone https://github.com/ryanhcode/rapier $ws\.native-src\rapier
git -C $ws\.native-src\rapier checkout 38e92f117590862481a53df6fc69a5d893e29186
git -C $ws\.native-src\rapier apply $ws\native\patches\rapier-profiling.patch

git clone --branch mc1.21.1-2.0.6-neoforge https://github.com/ryanhcode/sable $ws\.native-src\sable
git -C $ws\.native-src\sable apply $ws\native\patches\sable-rapier-profiling.patch

# vendor 目录需要另外生成（见 §4），然后就能 cargo build
```

（`sable-rapier-profiling.patch` 里包含 `Cargo.toml` 的相对 path 依赖和 `.cargo/config.toml`
的 vendored-sources 配置；`Cargo.lock` 的改动随构建自动生成，冲突时可以直接丢弃。）

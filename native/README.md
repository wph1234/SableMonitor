# native/ —— 已构建完成的 patched rapier

这里记录 **实际做出来、能编译、能在游戏里加载** 的那一版，而不是最初的设想稿。

当前状态（Sable 2.0.5 + 重命名后的工程）：

- Java 包 `dev.wphuujmhu.sablemonitor`，mod id `sablemonitor`，版本 `0.5.0-alpha`
- 原生库已按 Sable **2.0.5** 的 Rust 源码重新构建（不再是 2.0.3）
- JNI 导出已改成新包名（`..._sablemonitor_natives_...`）
- 已在干净的 dedicated server（`runVerifyServer`）上验证：natives 加载、`probe` 日志有非 0 数据

```
src/main/resources/natives/sable_rapier/sable_rapier_x86_64_windows.dll   1,700,352 B (1.62 MiB)
  sha256 2edb358a69de975abb901b8da0636047d9dd88bac38ab7d7516fb99ca22c3d25
  -> 由 .native-src/sable/sable_rapier/src/main/rust/target/release/sable_rapier.dll 拷贝而来
```

---

## 1. 改了哪些文件（以 `.native-src/` 下的实际代码为准）

本地工作副本（已加进 `.gitignore`）：

| 路径 | 说明 |
|---|---|
| `.native-src/rapier` | `ryanhcode/rapier` @ `38e92f1`（= Sable 2.0.5 的 `Cargo.toml` 里 pin 的 rev，两个版本没变） |
| `.native-src/sable` | `ryanhcode/sable` @ tag `mc1.21.1-2.0.5-neoforge`，本地分支 `patch-2.0.5` |
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

> 2.0.3 → 2.0.5 的官方 Rust 改动只有一处：`lib.rs` 里加了 `LogWriter`（吞掉 stdout 的
> `BrokenPipe`），并把 `fern` 的输出改成 `.chain(fern::Output::writer(...))`。
> 这一处已经合并进我们打过补丁的 `lib.rs`；`scene.rs`、`rust-toolchain.toml`、
> rapier 的 rev 在两个 tag 之间都没变。

### Java 侧同步改动

| 文件 | 改动 |
|---|---|
| `NativePhysicsProfile` / Rust 导出名 | 包名从 `sublevelmonitor` 改为 `sablemonitor`，JNI 符号随之改变（见 §2.2） |
| `PatchedNatives` | 解压目录改为镜像 Sable 2.0.5 的 `SableLoaderPlatform.getGameDirectory()`（拿不到就退回 `.sable/natives`）；文件名仍是内容寻址（`-<8 hex>`） |
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

## 3. 验证结果（重命名 + 2.0.5 之后重跑）

| 验证项 | 方法 | 结果 |
|---|---|---|
| rapier 补丁 + sable 补丁能编译 | `cargo build --release`（sable workspace，nightly） | ✅ `Finished release profile in 32s` |
| DLL 导出符号 | `dumpbin /exports` | ✅ 55 个导出，含上面两个 `sablemonitor` 符号 |
| Gradle 打包 | `gradlew build --offline` | ✅ `sablemonitor-0.5.0-alpha.jar` 794,815 B，含 DLL |
| jar 内 DLL 与构建产物一致 | SHA-256 对比 | ✅ `2edb358a…` 一致 |
| Sable 实际加载我们的 DLL | `gradlew runVerifyServer` | ✅ `loaded patched rapier natives from ...\sable_rapier_x86_64_windows-2edb358a.dll` |
| 采集有非 0 数据 | 同上 + `-Dsablemonitor.profile=true` | ✅ 每 20 tick：`step≈0.06ms/tick substeps=2 broad=0.003 narrow=0.008 solve=0.045` |
| 服务端正常启动 | 同上 | ✅ `Done (3.492s)!` |

> 这个空世界出生点附近没有 sub-level，所以 `pairs=0`、没有 per-sub-level 行。
> per-id 行的最后确认仍需要在有 sub-level 的世界里做（见 §5）。

---

## 4. 重建流程（我实际用的命令）

```powershell
$ws = "C:\Users\wangl\IdeaProjects\SablelMonitor"
$env:CARGO_HOME  = "$ws\.cargo-home"
$env:CARGO_NET_OFFLINE = "true"

# .native-src/sable 在分支 patch-2.0.5（= tag mc1.21.1-2.0.5-neoforge + 我们的补丁）
# 依赖已 vendored 到 .native-src/sable/sable_rapier/src/main/rust/vendor（169 个 crate）
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
```

临时换个依赖的最小验证服（`run-verify/` 里没有任何第三方 mod jar）：

```powershell
$env:JAVA_TOOL_OPTIONS = "-Dsablemonitor.profile=true -Dsablemonitor.profile.log=true"
.\gradlew.bat runVerifyServer --offline
```

`native/tools/vendor.py` 是补依赖用的（这台机器 schannel TLS 被拦，`cargo`/`curl`/`git`
用 schannel 都会 `SEC_E_NO_CREDENTIALS`，只有 Python 的 OpenSSL 能出网）：

```powershell
& "C:\Users\wangl\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" `
  native\tools\vendor.py "$ws\.native-src\sable\sable_rapier\src\main\rust\Cargo.lock" `
                         "$ws\.native-src\sable\sable_rapier\src\main\rust\vendor"
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
SableMonitor: probe t=1580 step=0.164ms/tick substeps=2 broad=0.003 narrow=0.009 island=0.001 solve=0.147
  | id=17 narrow~=0.031 solve~=0.284 pairs=9 island=2
```

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

git clone --branch mc1.21.1-2.0.5-neoforge https://github.com/ryanhcode/sable $ws\.native-src\sable
git -C $ws\.native-src\sable apply $ws\native\patches\sable-rapier-profiling.patch

# vendor 目录需要另外生成（见 §4），然后就能 cargo build
```

（`sable-rapier-profiling.patch` 里包含 `Cargo.toml` 的相对 path 依赖和 `.cargo/config.toml`
的 vendored-sources 配置；`Cargo.lock` 的改动随构建自动生成，冲突时可以直接丢弃。）

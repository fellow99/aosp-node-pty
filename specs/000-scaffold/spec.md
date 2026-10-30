# 000 · 工程搭建（scaffold）功能规格

> 模块：000-scaffold
> 需求编号：000-scaffold
> 需求名称：工程搭建
> 状态：已实现（as-built）
> 所属工程：aosp-node-pty
> 最后更新：2026-10-30
> 规划基线：[`docs/000-工程规划.md`](../../docs/000-工程规划.md)

## 1. 模块概述

### 1.1 目的 —— 为什么存在本模块

`aosp-node-pty` 是以 AAR 形式发布的、与某个 Node.js 版本严格配对的 Android 版
[node-pty](https://www.npmjs.com/package/node-pty) 原生件工程。000-scaffold 是该仓库的
**立项骨架**：在不产出最终二进制的前提下，确立仓库结构、构建系统、版本纪律、事实源文件、
投放与门禁工具、以及可复现的 CI 流水线，使后续的"交叉编译 `libpty_node.so`"工作只需在既定
骨架内填充实现，而无需再做工程决策。

### 1.2 解决的问题

- 生态无现成 Android arm64 node-pty 预编译件；内嵌 Node（`libnode.so`）无法在设备上 `node-gyp`。
- pty 原生件必须与 `aosp-node-runtime` **同 Node 版本、同 NODE_MODULE_VERSION** 编译，且产物必须带
  `DT_NEEDED: libnode.so` 才能在宿主 `RTLD_LOCAL` 加载引擎时解析 `napi_*` 符号。
- 需要一个镜像 `aosp-node-runtime` 的、可复现、可门禁、可发布的工程骨架，保证两条产品线同口径。

### 1.3 范围 —— 做什么 / 不做什么

**在范围内（000）：**

- Gradle 多模块骨架：root `settings.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、
  共享 `publish.gradle.kts`、`:node-pty` Android library 模块。
- 唯一事实源 `pty.lock.json`（版本 + 配对 Node + ABI + 门禁 + 出处）。
- 构建脚本：`scripts/fetch-node-pty-source.mjs`、`scripts/build-pty-android.sh`（M1 完整实现）。
- 投放与门禁工具：`tools/provision.mjs`（ELF + **DT_NEEDED libnode.so** 特有关卡）。
- CI：`.github/workflows/build-pty-android.yml`（拉配对 `libnode.so` 仅用于链接 → 编译 → 门禁 → 发 Release）。
- 文档与许可：`README.md` / `README_zh.md`、`node-pty/THIRD-PARTY-NOTICES.md`、`.gitignore`。
- 本规格文档套件。

**不在范围内（000）：**

- 不在本地产出/提交 `libpty_node.so`（二进制不入库；编译在 CI 完成）。
- 不实现任何嵌入 API / Kotlin/Java 桥（属 `aosp-node-bridge`）。
- 不提供 node-pty 的 JS 运行时封装（初版只发原生 `.so`，见规划 §10 开放问题 2）。
- 不发布 Maven Central（M3；脚手架仅保留发布面，默认 `RELEASE=false`）。
- 不扩多 ABI/多 API 矩阵（跟随 runtime，当前仅 arm64-v8a / API 24）。

## 2. 用户故事

- 作为**维护者**，我希望有一个镜像 `aosp-node-runtime` 的 Gradle 工程，使我能用一致的命令
  配置、构建、发布 pty 制品，而不必重新设计工程结构。
- 作为**维护者**，我希望 CI 从 `aosp-node-runtime` 同版本 Release 拉取 `libnode.so` **仅作链接用途**，
  从而保证 pty 的 NODE_MODULE_VERSION 与运行时严格对齐，且绝不 against 错误引擎版本编译。
- 作为**维护者**，我希望 `tools/provision.mjs` 在投放时强制校验 `DT_NEEDED` 含 `libnode.so`、
  `ET_DYN`、无 `PT_INTERP`、AArch64、16KB 对齐、sha/size，使任何不合格产物无法进入 AAR。
- 作为**消费方开发者**，我希望 README 清楚说明 `node-pty:<node>` 必须与 `node-runtime:<node>`
  同版本配对，以及 `.so` 的加载适配契约，避免 ABI/加载踩坑。
- 作为**发布工程师**，我希望默认 `RELEASE=false`、Token/GPG 只来自用户级
  `~/.gradle/gradle.properties`，从而避免误发与凭据泄漏。

## 3. 功能需求

### 3.1 Gradle 工程与坐标

- FR-000-001：root 工程名 MUST 为 `aosp-node-pty`，且 MUST 仅 include 一个模块 `:node-pty`。
- FR-000-002：模块 `:node-pty` MUST 是 Android library（`com.android.library`），
  namespace 为 `org.fellow99.nodepty`，`minSdk 24`、`compileSdk 36`，ABI 仅 `arm64-v8a`。
- FR-000-003：制品坐标 MUST 为 `io.github.fellow99:node-pty:<PTY_VERSION>`，
  其中 `PTY_VERSION` 初版固定 `24.22.1`，恒等于配对 Node 版本。
- FR-000-004：版本属性 MUST NOT 使用 `NODE_` 前缀（Gradle 9.4 保留给内置 Node 工具链），
  必须使用 `PTY_VERSION`。
- FR-000-005：工程 MUST 能在不投放任何 `.so` 的情况下完成 Gradle 配置/帮助任务
  （二进制缺失不应阻碍配置期求值）。

### 3.2 唯一事实源 pty.lock.json

- FR-000-010：仓库根 MUST 存在 `pty.lock.json`，含 `active`（node=24.22.1、abi=arm64-v8a）
  与至少一条 `releases` 记录。
- FR-000-011：release 记录 MUST 记录 `node`、`nodeAbi`（NODE_MODULE_VERSION=137）、`abi`、
  `androidApi`、产物名 `libpty_node.so`、配对 runtime 坐标、出处（node-pty/node headers/NDK/flags）、
  门禁集合与 `build` 序号。
- FR-000-012：门禁集合 MUST 包含 `neededLibnode`（本工程不可妥协项）以及
  `eTypeEtDyn / ptInterp / eMachineAArch64 / undefinedNapiResolvable / sha256Match / sizeMatch / pageAlign16k`。
- FR-000-013：在尚无 CI 产物时，`size`/`sha256` MUST 以占位（0/空串）表达并令 `status="draft"`，
  不得伪造哈希。

### 3.3 构建脚本（M1 可复现）

- FR-000-020：`scripts/fetch-node-pty-source.mjs` MUST 能下载指定 node-pty npm 包（不改源码）与
  对应版本 Node 头文件，并以参数/lock 固定版本。
- FR-000-021：`scripts/build-pty-android.sh` MUST 用 NDK clang++ 交叉编译 `src/unix/pty.cc` 为
  `libpty_node.so`，固化规划 §3.4 五条踩坑：
  1. MUST NOT 定义 `NODE_ADDON_API_ENABLE_MAYBE`；
  2. MUST 通过 `-Wl,--no-as-needed … -Wl,-l:libnode.so` 产生 `DT_NEEDED libnode.so`；
  3. 输出 MUST 命名为 `libpty_node.so` 并放入 AAR `jni/arm64-v8a/`；
  4. MUST 用与配对 runtime 同版本的 Node 头文件（NODE_MODULE_VERSION 一致）；
  5. MUST NOT 链接 `-lutil`（bionic 无 libutil，forkpty/openpty 在 libc）。
- FR-000-022：构建脚本 MUST 在编译后 `llvm-strip --strip-unneeded` 并产出元数据（size/sha256）。
- FR-000-023：脚本 MUST 接受 `--node / --api / --libnode / --out` 等参数，且在缺失 NDK/编译器时
  以非零码明确失败。

### 3.4 投放与门禁 provision.mjs

- FR-000-030：`tools/provision.mjs` MUST 支持 `--check`、`--inspect <so>`、
  `--out <dir> --from <so>`、`--node <ver>`、`--abi <abi>`。
- FR-000-031：退出码纪律 MUST 为 `0` 全过 / `2` 门禁不合格 / `1` 用法或 IO 错误。
- FR-000-032：门禁 MUST 断言 `e_type=ET_DYN`、`PT_INTERP=0`、`e_machine=AArch64`、
  `DT_NEEDED` 含 `libnode.so`、16KB 页对齐。
- FR-000-033：门禁 MUST 验证 `napi_*`/`node_*` 以**未定义引用**存在（由 `libnode.so` 运行期解析），
  对应 `undefinedNapiResolvable`。
- FR-000-034：投放时 MUST 仅复制 `libpty_node.so`（**绝不**复制 `libnode.so` / `libc++_shared.so`）。
- FR-000-035：当 lock 提供 sha/size 时 MUST 比对；draft（无锁值）时仅记录、不报失败。
- FR-000-036：投放后 MUST 写 `node-pty/src/main/resources/META-INF/node-pty.properties` 版本元数据。

### 3.5 GitHub Actions

- FR-000-040：workflow 文件 MUST 为 `.github/workflows/build-pty-android.yml`，触发为
  `workflow_dispatch`，入参含 `node_version`（默认 lock active）、`android_api`(24)、
  `node_pty_version`、`prerelease`。
- FR-000-041：CI MUST 安装 NDK r28c（28.2.13676358）与 host 工具。
- FR-000-042：CI MUST 从 `aosp-node-runtime` 同版本 GitHub Release 资产取 `libnode.so` 仅用于链接；
  取不到或版本不符 MUST 失败。
- FR-000-043：CI MUST 下载同版本 Node 头文件 + node-pty npm 源 + node-addon-api，编译 → strip →
  跑 provision 门禁 → 打包 `node-pty-v<node>-android-arm64-v8a.tar.gz`。
- FR-000-044：CI MUST 上传构建产物并发布/更新 GitHub Release（tag `v<node>`，clobber 资产、
  tag 固定到本次 commit、同步 prerelease）。

### 3.6 卫生、许可与文档

- FR-000-050：`.gitignore` MUST 忽略 `node-pty/src/main/jniLibs/**/*.so` 与构建/投放工作目录，
  保证二进制不入库。
- FR-000-051：MUST 提供 `node-pty/THIRD-PARTY-NOTICES.md` 声明 node-pty(MIT)、node-addon-api、NAPI 归属。
- FR-000-052：`README.md`/`README_zh.md` MUST 说明定位、坐标、与 runtime 的强配对、AAR 内容、
  构建方式、门禁、以及 §7.3 的加载适配契约（最小伪代码级）。
- FR-000-053：发布面 MUST 默认 `RELEASE=false`，凭据仅从用户级 gradle.properties 读取；
  MUST 提供 `gradle.properties.local.example`。

## 4. 关键实体

| 实体 | 说明 | 关键属性 |
|---|---|---|
| ReleaseRecord | lock 中一条 Node 版本配对记录 | node, nodeAbi, abi, androidApi, name, size, sha256, pairedRuntime, builtFrom, gates, build, status |
| GateSet | 产物必须满足的门禁集合 | neededLibnode, eTypeEtDyn, ptInterp, eMachineAArch64, undefinedNapiResolvable, sha256Match, sizeMatch, pageAlign16k |
| ElfInfo | provision 解析 ELF 得到的事实 | eType, eMachine, ptInterp, loadAligns, needed, soname, undefSymbols |
| BuildProvenance | 可复现构建出处 | nodePty, nodeHeaders, nodeAddonApi, ndk, flags, stripped |

## 5. 验收场景

### 场景：干净工程可配置

- Given 一份没有任何 `.so` 的全新检出
- When 维护者运行 Gradle 配置/帮助任务
- Then 配置期成功，模块 `:node-pty` 存在，坐标版本解析为 `24.22.1`。

### 场景：门禁拒绝缺引擎绑定的产物

- Given 一个 `ET_DYN`/AArch64 但 **没有** `DT_NEEDED libnode.so` 的 `.so`
- When 运行 `provision.mjs --inspect <so>` 或 `--out … --from <so>`
- Then `neededLibnode` 关卡失败，退出码为 `2`，且 `--out` 拒绝投放。

### 场景：CI 链接用引擎版本不符即失败

- Given 触发的 `node_version` 在 `aosp-node-runtime` 无同版本 Release 资产
- When CI 执行"拉取配对 libnode.so"步骤
- Then 该步骤失败，工作流转红，绝不产出 pty 制品。

### 场景：draft lock 不阻塞骨架

- Given lock 中该 release 的 size=0、sha256 为空、status=draft
- When 运行 `provision.mjs --inspect <合格 so>`
- Then sha/size 关卡仅记录不判失败，其余硬门禁照常判定。

### 场景：二进制不入库

- Given 投放后 `node-pty/src/main/jniLibs/arm64-v8a/libpty_node.so` 存在
- When 运行 `git status`
- Then 该 `.so` 被忽略，不出现在待提交列表。

## 6. 非功能需求

- **可复现**：干净 GitHub runner 上单次 workflow 可重出口径一致的产物（同 flags/NDK/源版本）。
- **可移植**：构建脚本面向 Linux runner（CI）；provision.mjs 跨平台（纯 Node，无原生依赖）。
- **安全卫生**：凭据不入库、默认不发布、二进制不入库。
- **一致性**：工程结构、发布面、退出码、lock 纪律与 `aosp-node-runtime` 同口径。

## 7. 假设与约束

- 配对 `aosp-node-runtime` 已发布 `24.22.1`（含 GitHub Release 资产与 Maven Central 制品）。
- CI 环境为 `ubuntu-24.04`，NDK r28c 提供 `aarch64-linux-android24-clang++`。
- 本地仅安装 NDK r27.2，故 000 **不要求本地产出 `.so`**；交叉编译正确性由 CI 验证。
- node-pty 上游版本钉选策略遵循规划 §10-1：固定 + 显式升级（初版在 lock/脚本参数中固定）。

## 8. 依赖

- **上游**：[node-pty](https://www.npmjs.com/package/node-pty)（MIT，源码不改）、`node-addon-api`、
  Node 官方 `node-v<ver>-headers.tar.gz`、Android NDK r28c。
- **伴生工程**：[`aosp-node-runtime`](https://github.com/fellow99/aosp-node-runtime) `24.22.1`
  （提供链接期 `libnode.so` 与运行期引擎；运行期由消费方 APK 同时携带）。
- **下游消费**：任意内嵌 Node 的 Android 工程（如 `aosp-node-bridge` 一线），按规划 §7 契约接入。

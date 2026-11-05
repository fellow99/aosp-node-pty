# 101-probe-app · 设备回程探针载体（probe-app）功能规格

> 模块：101-probe-app
> 需求编号：101-probe-app
> 需求名称：probe-app 设备回程探针载体
> 状态：规划中（spec-first，先文档后实现；本批只实现到「APK 构建通过 + 载荷打包」，设备运行由编排者统一排队）
> 所属工程：aosp-node-pty
> 最后更新：2026-11-05
> 规划基线：[`../../docs/000-工程规划.md`](../../docs/000-工程规划.md) §7.4（建议消费方自跑 `spawn sh -c 'echo PTY_ROUNDTRIP_OK'` 回程探针）

## 1. 目的 —— 为什么存在本模块

`aosp-node-pty` 只产出原生件 `libpty_node.so`（N-API addon，`DT_NEEDED: libnode.so`），不含
Java/Kotlin 桥、不含 node-pty 的 JS 运行时封装。因此需要一个**独立的 Android 探针 App**
（`probe-app`），把以下可证伪判据落到真实设备上：

- 载荷已随 APK 就位（`nativeLibraryDir/libpty_node.so`，arm64-v8a）；
- `System.loadLibrary("pty_node")` 的加载/链接行为与具体失败原因；
- 版本配对事实（实际 `.so` 元数据 vs `pty.lock.json` 记录）；
- spawn 回程探针的**判据定义与前置条件**（⬜ 后续批次，本批不实现）。

本模块**不新增产品能力**，是消费方接入契约（`System.loadLibrary("pty_node")`、与 node-runtime
同版本强配对、node-pty JS 适配由消费方自备）的验证实现。

> **版本配对事实（2026-11-05 核对）**：本仓 GitHub Release 最新为 **v22.23.4**（本次实际下载，
> 见 plan §1.2），其 `.so` 为 node 22.23.4 / NODE_MODULE_VERSION **127**；而 `pty.lock.json`
> 的 `active` 为 node **26.13.0** / NODE_MODULE_VERSION **147**。即使本批 APK 构建成功，在配
> 对 runtime 26.13.0 的设备上 `require('node-pty')` 仍会 NODE_MODULE_VERSION 不匹配。故本批只
> 把「载荷就位 / 加载 / 链接 / 版本记录」作为可验证项，**spawn 回程一律标 ⬜**，不伪造 PASS。

## 2. 范围

**在范围内（101-probe-app）：**

- 独立 Gradle 工程 `probe-app/`（参照 `aosp-node-cli/probe-app` 结构，**独立构建，不并入本仓
  root settings.gradle.kts**——000-scaffold FR-000-001 单模块红线不动）。
- `app/src/main/jniLibs/arm64-v8a/libpty_node.so`：本批从本仓 GitHub Release v22.23.4 归档下载
  并校验 sha256 后占位投放（git 忽略；构建入包）。
- 单一 `MainActivity`（Java）：RUN ALL PROBES 按钮 + `--ez autorun true` 无头 + 逐探针超时 +
  `files/probe-report.json` 三态报告。
- 探针：PRB-01 载荷就位（arm64）/ PRB-02 预载 libnode.so / PRB-03 加载 addon / PRB-04 版本记录
  （fact）/ PRB-05 spawn 回程判据（⬜，仅声明不执行）。
- 本规格文档套件（spec/plan/tasks/test-cases）与 `logs/20261105-1/DEV_CHECKLIST.md`。

**不在范围内（101-probe-app）：**

- 不实现 spawn 回程执行（⬜ 后续批次；前置：匹配 node-runtime + node-pty JS 封装，见 FR-101-PROBE-005）。
- 不并入本仓产品或发布面（不发布 APK / AAR / Maven）。
- 不修改 root `settings.gradle.kts` / `gradle.properties` / `pty.lock.json`。
- 不捆绑 `libnode.so` / `libc++_shared.so`（配对 runtime 供给件，本工程只放产物契约 `.so`）；
  加载探针因此对「预载」给出 BLOCKED 说明而非伪造成功。
- 不做 node-pty 的 JS 运行封装（消费方自备 npm 包 node-pty）。

## 3. 功能需求

> 术语：探针（Probe）= `MainActivity` 中一条带 `id` / 判据 check 的记录；判据返回 `null` = PASS、
> `"BLOCKED:…"` = BLOCKED、`"PASS-…"` 前缀 = 事实形态 PASS、其余 = FAIL。

### 3.1 载荷完整性（PRB-01）

- **FR-101-PROBE-001（MUST）**：`libpty_node.so` MUST 作为 `arm64-v8a` jniLibs 随 debug APK 打包，
  构建后在 `nativeLibraryDir/libpty_node.so` 物理就位（`useLegacyPackaging=true` +
  `android:extractNativeLibs="true"`）。PRB-01 MUST 断言该文件存在、大小非 0，且 ELF 头为
  `ELFCLASS64`/小端/`e_machine=AArch64(183)`；并计算实际 sha256 与 lock 记录比对（fact 形态）。
- **FR-101-PROBE-002（MUST）**：本批投放的 `.so` 必须来自本仓 GitHub Release **v22.23.4** 归档
  `node-pty-v22.23.4-android-arm64-v8a.tar.gz`（sha256 `3a52ad91…c7e7a`），解包 `libpty_node.so`
  （sha256 `7f514ee5…25220`、71,120 B）；投放记录真实来源，不得伪造。

### 3.2 加载与链接（PRB-02 / PRB-03）

- **FR-101-PROBE-003（MUST）**：PRB-02 预载 libnode.so——若消费方注入的匹配 runtime 已在进程内
  （`System.loadLibrary("node")` 成功），MUST 记录兼容性 PASS；否则 MUST 判 BLOCKED 并写明
  「无匹配 runtime 注入」（`.so` 的 `DT_NEEDED: libnode.so` 需由引擎装载期解析，本工程不捆绑引擎）。
- **FR-101-PROBE-004（MUST）**：PRB-03 加载 addon——`System.loadLibrary("pty_node")` 成功即证明
  `DT_NEEDED: libnode.so` 符号可解析（链接成立）。失败时 MUST 如实记录具体原因（如
  `dlopen failed: library "libnode.so" not found`、或 NODE_MODULE_VERSION 不匹配的 logcat 文案 |
  `undefined symbol: napi_*`），不得伪造成 PASS。

### 3.3 版本配对记录与 spawn 判据（PRB-04 / PRB-05）

- **FR-101-PROBE-005（MUST）**：PRB-04 版本记录为 fact——MUST 记录 `pty.lock.json` 的
  `active(node=26.13.0, nodeAbi=147)` 与本批实际 `.so` 配对(node=22.23.4, nodeAbi=127)的**差异事实**，
  并记录实际 `.so` sha256 是否与 lock release 记录一致；只记录，不作 PASS/FAIL 价值判断。
- **FR-101-PROBE-006（MUST）**：spawn 回程判据（⬜）——探针 `spawn sh -c 'echo PTY_ROUNDTRIP_OK'`
  输出回程即为 device 级链接+原生可用性的最终判据。其**前置条件如下，任一不满足即 ⬜ 后续批次**：
  1. 设备内进程存在**与 `.so` 同 NODE_MODULE_VERSION** 的 `libnode.so`（node 22.23.4 ↔ runtime 22.23.4）；
  2. 消费方自备 node-pty JS 封装（npm 包 node-pty）并解析到本 `.so`。
  本批不实现该探针执行，仅在 test-cases 与报告字段中以 `BLOCKED(⬜ 后续批次)` 声明。

### 3.4 报告与无头（PRB-全局）

- **FR-101-PROBE-007（MUST）**：报告——每条探针 `PASS/FAIL/BLOCKED` + `detail` 汇总为
  `files/probe-report.json`（含 `runAt` / `device` / `abi` / `expectedPairing` / `probes[]`）；
  每探针独立超时（worker 线程 join 兜底，超时记 FAIL 原因 `timeout`），单探针异常不中断整体。
- **FR-101-PROBE-008（MUST）**：无头自动化——`adb shell am start -n …/MainActivity --ez autorun true`
  启动即自动跑批，供编排者脚本采集报告。
- **FR-101-PROBE-009（MUST）**：判据纪律——对「本批必然不达」的项（device 上 spawn 回程、
  PRB-02/03 无匹配 runtime 注入）如实标 BLOCKED/FAIL 并写明原因；禁止伪造 PASS（对齐
  000-scaffold 与 aosp-node-cli 101-probe-app 的 C6 判据纪律）。

## 4. 用户故事

- 作为 **消费方开发者**，我希望有一个可复现的探针 App，在接入 node-pty 前先验证「`.so` 已随
  APK 就位、arm64、加载失败原因可读」，从而在集成 cycle 首端就暴露打包/ABI/版本问题。
- 作为 **测试/QA**，我希望探针在真实设备上自动跑批并产出三态 JSON 报告，从而给出可证伪的
  载荷断言，而不是靠肉眼 logcat。
- 作为 **版本维护者**，我希望探针把「Release 实际版本 vs lock 配对版本」作为事实记录，
  使 NODE_MODULE_VERSION 偏差在早期被显式暴露，而非等到 `require('node-pty')` 崩。
- 作为 **编排者**，我希望 `--ez autorun true` + `probe-report.json` 可直接排队采集，
  无需人手点 App。

## 5. 验收场景

1. **载荷入包**：Given 已从 v22.23.4 归档投放 `libpty_node.so`（sha 校验通过）；When
   `gradle :app:assembleDebug` 构建成功且 `unzip -l` 查看 APK；Then
   `lib/arm64-v8a/libpty_node.so` 在包内，且 `nativeLibraryDir` 物理存在（PRB-01 可 PASS）。
2. **加载如实记录**：Given APK 只带 `libpty_node.so`、无注入 libnode.so；When 设备跑 PRB-02/03；
   Then 判 BLOCKED 并写明「无匹配 runtime 注入 / dt_needed libnode.so 无法解析」原因，不伪造成功。
3. **版本事实记录**：Given `pty.lock.json` active=26.13.0、实际 `.so` 为 22.23.4；When 跑 PRB-04；
   Then 报告如实记录 nodeAbi 127 vs 147 的差异事实与 sha 比对结果。
4. **spawn ⬜**：Given 本批无匹配 runtime + 无 node-pty JS；When 列出 PRB-05；Then 恒为
   `BLOCKED(⬜ 后续批次)`，不执行、不判 PASS。
5. **无头报告**：Given `am start … --ez autorun true`；When 自动跑批结束；Then
   `files/probe-report.json` 存在且可 `adb run-as` 拉回。

## 6. 关键实体

| 实体 | 说明 | 关键字段 |
|---|---|---|
| Probe | 一条探针定义 | id(PRB-xx), title, timeoutMs, check |
| Out | 单探针采集 | verdict, detail, ms, values（是否存在/大小/sha/异常文案） |
| Report | `files/probe-report.json` | runAt, device, abi, expectedPairing(active vs artifact), probes[] |
| LockRef | `pty.lock.json` 配对事实源 | active.node=26.13.0/nodeAbi=147; release v22.23.4/sha |
| Artifact | 本批 `.so`（事实） | node=22.23.4, nodeAbi=127, size=71120, sha=7f514ee5…, DT_NEEDED=[libnode.so, libc++_shared.so, libm, libdl, libc] |

## 7. 非功能需求

- **平台**：仅 arm64-v8a（唯一 ABI，跟随 runtime/ci 矩阵）；minSdk 24、compileSdk 36。
- **App 域 / 无外部网络**：探针运行在本 App 进程域，不请求 `INTERNET` 权限、不访问外部网络；
  全部判据可在离线设备执行（运行时下载/网络不依赖）。
- **可靠**：逐探针超时兜底、单探针异常隔离、报告始终可写（即使部分探针失败）。
- **诚实**：版本配对 / NODE_MODULE_VERSION / spawn ⬜ 均为如实记录，禁止伪造 PASS（C6 纪律）。
- **卫生**：`.so` 不入 git；probe-app 构建产物不入库；不发布。

## 8. 依赖

| 依赖 | 锚点 | 说明 |
|---|---|---|
| 本仓 Release v22.23.4 | `https://github.com/fellow99/aosp-node-pty/releases/tag/v22.23.4` | `.so` 来源（归档 sha `3a52ad91…`，.so sha `7f514ee5…`） |
| `pty.lock.json` | [`../../pty.lock.json`](../../pty.lock.json) | 配对版本/NODE_MODULE_VERSION/sha 唯一事实源 |
| node-pty JS 封装（⬜） | npm 包 `node-pty` | spawn 回程探针的前置（PRB-05，后续批次消费方自备） |
| node-runtime（⬜ 匹配版） | `aosp-node-runtime` | 提供加载期 `libnode.so`；当前 active 26.13.0 与本批 22.23.4 不匹配 |
| 参照实现 | `aosp-node-cli/probe-app` | 工程结构与探针/报告/无头模式同构 |

# 101-probe-app 技术方案（As-Built：本批到「构建通过」）

> 模块：101-probe-app
> 对应规格：[spec.md](./spec.md)
> 最后更新：2026-11-05（spec-first；APK 构建通过 + 载荷打包验证；设备运行由编排者统一排队）
> 范围：probe-app 独立工程 + 载荷投放 + PRB-01…05 探针实现 + `logs/20261105-1/DEV_CHECKLIST.md`

## 1. 技术上下文

### 1.1 运行环境

| 项 | 值 |
|---|---|
| Gradle | 9.4.1 发行版（`D:/node-bridge-workspace/aosp-node-cli/.dist/gradle-dist/gradle-9.4.1/bin/gradle`） |
| AGP | `com.android.application` 9.2.1（与 aosp-node-pty root / aosp-node-cli probe-app 同版本） |
| compileSdk / minSdk / targetSdk | 36 / 24 / 35 |
| Java | 17（source/target compatibility） |
| ABI | arm64-v8a（唯一） |
| SDK | `C:/Users/Administrator/AppData/Local/Android/Sdk`（probe-app/local.properties，git 忽略） |
| AndroidX | 不用（`android.useAndroidX=false`，纯 AOSP Activity） |
| .so 来源 | 本仓 GitHub Release **v22.23.4**（本次实际下载成功，见 §1.2） |

### 1.2 载荷获取与核验（2026-11-05 实测）

- URL：`https://github.com/fellow99/aosp-node-pty/releases/download/v22.23.4/node-pty-v22.23.4-android-arm64-v8a.tar.gz`（+ `.sha256`）。
- 代理：本机 `github.com` 直连不通，经 `HTTP(S)_PROXY=http://127.0.0.1:7890` 下载成功。
- 核验（全部通过，与 `pty.lock.json` v22.23.4 记录一致）：
  - 归档 sha256 `3a52ad91c739590850048ce53423af0ad9e2b43c83d35d3b70fc9c08088c7e7a`（对 `.sha256` 与 lock 双一致）；
  - 包内 `libpty_node.so` sha256 `7f514ee596399f13b5965a391fc9c3ed27b3236a7fbcee13d51983c1e3285220`，size 71,120 B；
  - `file`：`ELF 64-bit LSB shared object, ARM aarch64, dynamically linked, for Android 24, built by NDK r30, stripped`；
  - ELF `DT_NEEDED = [libnode.so, libc++_shared.so, libm.so, libdl.so, libc.so]`（运行期需求 `libnode.so` 由匹配 runtime 供给）。
- 投放：`probe-app/app/src/main/jniLibs/arm64-v8a/libpty_node.so`（`.so` 不入 git，probe-app/.gitignore 忽略）。

### 1.3 版本配对事实（诚实记录）

| 项 | 值 |
|---|---|
| `pty.lock.json` `active` | node **26.13.0** / nodeAbi **147**（runtime 侧配对仍是 26.13.0） |
| 本批实际 `.so`（Release v22.23.4） | node **22.23.4** / nodeAbi **127** |
| 结论 | Release 最新为 v22.23.4，与当前 runtime 配对 **不匹配**；即使 APK 构建成功，device 上
  `require('node-pty')` 会 NODE_MODULE_VERSION 不匹配 ⇒ **spawn 回程 ⬜ 后续批次** |
| 本批可验证 | 载荷就位 / 加载尝试（如实 BLOCKED/FAIL）/ 链接事实 / 版本记录 |

## 2. 与规划基线/宪法的合规

- ✅ 单模块红线：probe-app **独立 Gradle 工程**，**不并入** root `settings.gradle.kts`（FR-000-001 不动；
  与 aosp-node-cli probe-app 同策略）。
- ✅ 二进制不入库：`probe-app/.gitignore` 忽略 `app/src/main/jniLibs/**/*.so` 与构建产物。
- ✅ 版本纪律：所有版本/sha 引用唯一事实源 `pty.lock.json` + 实际 Release 元数据，不写死副本。
- ✅ 判据纪律（C6）：spawn ⬜ / 加载 BLOCKED / 版本差异 fact，均如实记录，禁止伪造 PASS。
- ✅ 卫生：不发布、无 INTERNET 权限、App 域、无外部网络依赖。

## 3. 关键设计决策与理由

### 3.1 为什么 probe-app 独立构建、不并入 root

root 的 000-scaffold FR-000-001 规定 root 仅 include `:node-pty`（单模块红线）。probe-app 是
**开发/验收载体**，不是产品模块；参照 `aosp-node-cli/probe-app` 的「独立 Gradle 工程」策略，
自带 `settings.gradle.kts` + `build.gradle.kts` + `gradle.properties`。因此任务描述的
`gradle :probe-app:assembleDebug` 字面任务名（隐含父工程 include）不适用——实际在 probe-app 目录内
以 root project=probe-app → 模块 `:app` 构建：`gradle -p probe-app :app:assembleDebug`，语义等价
（产出 `app/build/outputs/apk/debug/` 的唯一 APK）。`probe-app/settings.gradle.kts` 的
`rootProject.name` 设为 `pty-probe-app` 以便与 parent 区分。

### 3.2 为什么 jniLibs 只放 libpty_node.so、不放 libnode.so

产物契约（spec §2 / lock matrix note）：pty AAR 只携 `libpty_node.so`；`libnode.so`/`libc++_shared.so`
由 node-runtime 配对供给。本批 APK 只放契约 `.so`，因此：

- PRB-01（载荷就位）可完整验证；
- PRB-02/03（预载/加载）在无匹配 runtime 注入时**如实 BLOCKED**，写明 `dlopen 缺 libnode.so` 或
  NODE_MODULE_VERSION 原因，而非伪造成功或放一个「测试搭台」的非契约 libnode.so（违反诚实纪律）。
- 选型说明：可选方案「runtimeOnly node-runtime:24.2.0 预载 libnode.so」**未采纳**——24.2.0 与
  本批 .so(22.23.4) NODE_MODULE_VERSION 不同，只会制造错误配对噪音，且引入外部 Maven 依赖与网络
  （违反「无外部网络」NFR）。

### 3.3 为什么 pack `useLegacyPackaging=true` + `extractNativeLibs=true`

PRB-01 需断言 `nativeLibraryDir/libpty_node.so` **物理存在**（而非仅 APK 内条目）。legacy packaging
将 `.so` 解包到 `nativeLibraryDir`（与 aosp-node-cli probe-app 同口径），使「存在性 + ELF 头 + sha」
判据在设备上可直接读文件实现。

### 3.4 探针判据形态

| 探针 | 判据 | 预期（本批设备无匹配 runtime） |
|---|---|---|
| PRB-01 | 存在 + size>0 + ELF64/AArch64 + sha==lock | PASS（载荷就位） |
| PRB-02 | `System.loadLibrary("node")` 成功；否则 BLOCKED：无匹配 runtime 注入 | BLOCKED |
| PRB-03 | `System.loadLibrary("pty_node")` 成功=链接可解析；失败记录原因 | BLOCKED（dlopen 缺 libnode.so）/ 或 FAIL（版本不匹配时） |
| PRB-04 | 记录 active vs artifact 配对差异 + 实际 sha vs lock（fact） | PASS-FACT（记录，不断言正误） |
| PRB-05 | spawn `sh -c 'echo PTY_ROUNDTRIP_OK'`（⬜） | BLOCKED(⬜ 后续批次)，不执行 |

## 4. 数据模型（probe-report.json）

```jsonc
{
  "runAt": "2026-11-05T…",
  "device": "<MODEL> <API> abi=arm64-v8a",
  "abi": "arm64-v8a",
  "expectedPairing": {
    "lockActive": { "node": "26.13.0", "nodeAbi": 147 },
    "artifact":     { "node": "22.23.4", "nodeAbi": 127, "sha256": "7f514ee5…25220", "size": 71120 }
  },
  "probes": [
    { "id": "PRB-01", "verdict": "PASS", "detail": "", "ms": 1, "values": {…} },
    { "id": "PRB-02", "verdict": "BLOCKED", "detail": "无匹配 runtime 注入（libnode.so 未预载）", "ms": 0 },
    …
  ]
}
```

## 5. 实现策略

### 5.1 目录结构（落地）

```
aosp-node-pty/
├── specs/101-probe-app/{spec,plan,tasks,test-cases}.md   # 本文档套件
├── logs/20261105-1/DEV_CHECKLIST.md                      # 开发核对（本批）
└── probe-app/                                            # 独立 Gradle 工程
    ├── settings.gradle.kts                               # rootProject.name=pty-probe-app, include(":app")
    ├── build.gradle.kts                                  # com.android.application 9.2.1 apply false
    ├── gradle.properties                                 # jvmargs / useAndroidX=false / PROBE_APP_VERSION=22.23.4
    ├── local.properties                                  # sdk.dir（git 忽略）
    ├── .gitignore                                        # build/.gradle/local.properties/jniLibs .so
    └── app/
        ├── build.gradle.kts                              # namespace org.fellow99.ptyprobe, minSdk24, ABI 仅 arm64-v8a, legacy packaging
        └── src/main/
            ├── AndroidManifest.xml                       # extractNativeLibs=true, 无 INTERNET
            ├── java/org/fellow99/ptyprobe/MainActivity.java  # 单一 Activity，RUN ALL PROBES + autorun
            └── jniLibs/arm64-v8a/libpty_node.so          # 本批投放（git 忽略）
```

### 5.2 MainActivity 探针 runner

- `probes()` 声明式数组：`Probe(id, title, timeoutMs, Check)`；`Check` 返回 `null`=PASS、
  `BLOCKED:…`=BLOCKED、`PASS-…`=fact PASS、其余=FAIL（同 aosp-node-cli 探针纪律）。
- 每探针在独立 worker 线程内执行 + `join(timeoutMs)` 兜底 → 超时记 FAIL（`detail="timeout"`）。
- 加载类探针（PRB-02/03）用 try/catch 捕获 `UnsatisfiedLinkError`/`Throwable`，detail 记录
  `e.getClass().getSimpleName(): e.getMessage()`（失败时如实写），并通过 `e.getMessage()` 判别是否
  `NODE_MODULE_VERSION` / `not found` / `undefined symbol`。
- sha256（PRB-01/04）：`MessageDigest.getInstance("SHA-256")` 流式计算 71 KB 文件。
- ELF 头（PRB-01/04）：读前 20 字节断言 `e_ident[0..3]=0x7f ELF`、`[4]=2`(ELFCLASS64)、`[5]=1`(小端)、
  `e_machine(offset18, LE uint16)=183`(AArch64)。
- `expectedPairing` 常量来自 `pty.lock.json` + 实际 Release 元数据（spec FR-101-PROBE-005 事实）：
  `LOCK_ACTIVE_NODE=26.13.0/147`、`ARTIFACT_NODE=22.23.4/127`、`ARTIFACT_SHA=7f514ee5…25220`、`ARTIFACT_SIZE=71120`。
- 报告写 `filesDir/probe-report.json`（`getFilesDir()`，debug App `run-as` 可拉回），每次跑批覆盖。

### 5.3 并发/异常与超时

- `runAll()` 跑在全新线程（非 UI 线程），UI `log()` 经 `runOnUiThread`。
- 单探针 `try/finally` 保证 verdict 必填、异常隔离；prerun 前置（全部判据的静态部分）失败不阻断后续探针。

## 6. 构建与验证

```bash
# 构建（Gradle 9.4.1 发行版；在 probe-app/ 内，root 工程即 pty-probe-app → 模块 :app）
cd D:/node-bridge-workspace/aosp-node-pty/probe-app
D:/node-bridge-workspace/aosp-node-cli/.dist/gradle-dist/gradle-9.4.1/bin/gradle :app:assembleDebug --console=plain
# 产物 APK
app/build/outputs/apk/debug/app-debug.apk
# 载荷入包核验（unzip -l 应含 lib/arm64-v8a/libpty_node.so）
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -i pty
# 设备运行（由编排者统一排队；本批不执行）
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.fellow99.ptyprobe/.MainActivity --ez autorun true
adb shell run-as org.fellow99.ptyprobe cat files/probe-report.json
```

> 说明：任务所给的 `gradle :probe-app:assembleDebug` 隐含父工程 include 模式，与 000 单模块红线
> 冲突；实际以 `gradle -p probe-app :app:assembleDebug`（或 probe-app 目录内 `gradle :app:assembleDebug`）
> 等效完成 `:probe-app` 语义，产出唯一 debug APK。

## 7. 测试与验证结果（本批）

- Gradle 9.4.1 + AGP 9.2.1 配置期/构建期：BUILD SUCCESSFUL（见 §6 命令）。
- `unzip -l`：`lib/arm64-v8a/libpty_node.so` 在包内（size 71,120 与源一致，未 strip 篡改样本）。
- 静态核验：投放 .so 与 Release 归档 sha 一致（见 §1.2）。
- 设备运行：⬜（本批由编排者统一排队；探针 PRB-02/03 预计 BLOCKED，PRB-01/04 预计 PASS，见 plan §3.4）。

## 8. 文件清单

| 文件 | 用途 | 对应 FR |
|---|---|---|
| `specs/101-probe-app/spec.md` | 技术无关功能规格 | FR-101-PROBE-001…009 |
| `specs/101-probe-app/plan.md` | 本技术方案 | 全部 |
| `specs/101-probe-app/tasks.md` | 任务拆解 | 全部 |
| `specs/101-probe-app/test-cases.md` | PRB-xx 判据 + 实跑前置 | PRB-01…05 |
| `probe-app/settings.gradle.kts` | 独立工程声明 | FR-101-PROBE-001 |
| `probe-app/build.gradle.kts` | AGP 插件 | — |
| `probe-app/gradle.properties` | jvmargs / PROBE_APP_VERSION | — |
| `probe-app/.gitignore` | .so 与构建产物忽略 | — |
| `probe-app/app/build.gradle.kts` | app 模块（arm64/jniLibs/legacy packaging） | FR-101-PROBE-001 |
| `probe-app/app/src/main/AndroidManifest.xml` | extractNativeLibs、无 INTERNET | — |
| `probe-app/app/src/main/java/org/fellow99/ptyprobe/MainActivity.java` | 探针载体 + 报告 + 无头 | FR-101-PROBE-003…009 |
| `probe-app/app/src/main/jniLibs/arm64-v8a/libpty_node.so` | 本批投放载荷（git 忽略） | FR-101-PROBE-001/002 |
| `logs/20261105-1/DEV_CHECKLIST.md` | 开发核对记录 | — |

## 9. 执行结果与遗留

- **本批完成**：probe-app 工程 + 载荷投放 + PRB-01…05 + 构建通过 + 打包核验 + 文档。
- **遗留（⬜ 后续批次）**：device 运行（编排排队）；spawn 回程探针（需匹配 runtime 22.23.4 + node-pty JS）；
  与 runtime 26.13.0 配对版本对齐后的 NODE_MODULE_VERSION 全链路验证。

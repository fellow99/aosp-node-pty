# 101-probe-app 测试用例（PRB-xx 判据 + 实跑前置）

> 范围：probe-app 探针判据正典。**本批只到「构建通过 + 载荷打包」**；设备执行为 ⬜，由编排者统一
> 排队。⭐ = 实跑前置不满足 → ⬜（BLOCKED 不执行）。

## 版本配对基线（诚实事实，2026-11-05）

| 项 | 值 |
|---|---|
| `pty.lock.json` active | node **26.13.0** / nodeAbi **147**（runtime 侧配对） |
| 本批实际 `.so`（Release v22.23.4） | node **22.23.4** / nodeAbi **127** / sha `7f514ee5…25220` / 71,120 B |
| 结论 | Release 最新 v22.23.4 ≠ runtime 配对 26.13.0 ⇒ device `require('node-pty')` NODE_MODULE_VERSION 不匹配 ⇒ **spawn ⬜** |

## ⭐ 实跑前置（device 执行前须满足；编排者统一排队）

1. **载荷**：APK 含 `lib/arm64-v8a/libpty_node.so`（本批已验证）。
2. **设备**：arm64-v8a 真机或 arm64 模拟器（x86_64/翻译镜像禁止冒充）。
3. **输出可回拉**：debug 包可 `adb run-as org.fellow99.ptyprobe cat files/probe-report.json`。
4. **（PRB-02/03 的敏感前置）**：进程内预载**与 .so 同 NODE_MODULE_VERSION** 的 `libnode.so`
   （node 22.23.4）。本批 APK **未捆绑** libnode.so ⇒ 该前置不满足，PRB-02/03 预期 BLOCKED——
   **如实记录原因，禁止伪造 PASS**。

## PRB-xx 判据

| ID | 用例 | 判据（Check 返回 null=PASS） | 本批状态 |
|---|---|---|---|
| PRB-01 | 载荷就位 | `nativeLibraryDir/libpty_node.so` 存在且 size>0；ELF 头 `7f ELF`/CLASS64/小端/`e_machine=183(AArch64)`；sha256==`7f514ee5…25220` | ✅ 可验证（构建打包） |
| PRB-02 | 预载 libnode.so 兼容性 | `System.loadLibrary("node")` 成功=PASS；未注入匹配 runtime → `BLOCKED:无匹配 runtime 注入`（detail 记录原因） | ⭐⬜ 实跑前置 4 不满足 → 预期 BLOCKED |
| PRB-03 | 加载 addon（链接解析） | `System.loadLibrary("pty_node")` 成功=DT_NEEDED libnode.so 可解析=PASS；失败 → 如实记录原因（`dlopen failed: library "libnode.so" not found` / NODE_MODULE_VERSION 不匹配 | `undefined symbol`） | ⭐⬜ 实跑前置 4 不满足 → 预期 BLOCKED/FAIL |
| PRB-04 | 版本配对记录（fact） | 记录 `expectedPairing`：lockActive(node=26.13.0,nodeAbi=147) vs artifact(node=22.23.4,nodeAbi=127)；实际 .so sha==lock（`7f514ee5…`）；只记录，不作 PASS/FAIL 价值判断 → `PASS-FACT` | ✅ 可验证 |
| PRB-05 | spawn 回程 ⬜ 后续批次 | 前置缺失（匹配 runtime + node-pty JS）→ 恒 `BLOCKED(⬜ 后续批次，前置：node 22.23.4 匹配 runtime + node-pty JS 封装)`；**不执行 spawn** | ⭐⬜ 恒 BLOCKED |

> 判据纪律：PRB-02/03/05 在设备上以 BLOCKED（前置缺失）如实登记，detail 必须给出具体原因；
> 不得因「预期 BLOCKED」而跳过登记或伪造 PASS（spec FR-101-PROBE-009 / C6）。

## 构建侧用例（本批验证）

| ID | 用例 | 步骤 | 期望 |
|---|---|---|---|
| B-01 | 独立工程可配置 | `gradle -p probe-app projects`（Gradle 9.4.1） | 列出 `:app`；root=pty-probe-app；不触碰父工程 settings |
| B-02 | debug 构建 | `gradle -p probe-app :app:assembleDebug --console=plain` | BUILD SUCCESSFUL；APK 生成于 `probe-app/app/build/outputs/apk/debug/app-debug.apk` |
| B-03 | 载荷入包 | `unzip -l …/app-debug.apk` | 含 `lib/arm64-v8a/libpty_node.so`（71,120 B） |
| B-04 | 源一致性 | 包内 .so sha vs 投放 .so sha vs Release 归档 sha | 三者一致（`7f514ee5…`） |
| B-05 | 无 INTERNET | grep manifest | 无 `android.permission.INTERNET` |

## 设备侧用例（⬜ 编排者统一排队；本批不执行）

| ID | 用例 | 步骤 | 期望 |
|---|---|---|---|
| D-01 | 无头跑批 | `adb shell am start -n org.fellow99.ptyprobe/.MainActivity --ez autorun true` | 自动跑批完成 |
| D-02 | 报告回拉 | `adb shell run-as org.fellow99.ptyprobe cat files/probe-report.json` | JSON 含 runAt/device/abi/expectedPairing/probes[PRB-01…05] |
| D-03 | 三态 | 读报告 probes[] | PRB-01 PASS / PRB-02 BLOCKED(无 matching runtime) / PRB-03 BLOCKED 或 FAIL(如实原因) / PRB-04 PASS-FACT / PRB-05 BLOCKED(⬜) |

## 禁止判据（C 系列）

- C1 不得伪造 PASS：本批必然不达项（PRB-02/03 前置缺失、PRB-05 ⬜）一律如实 BLOCKED。
- C2 不得用非契约 libnode.so 搭台：不捆绑/不 runtimeOnly node-runtime（避免制造错误配对噪音）。
- C3 不得在 x86_64/翻译镜像冒充 arm64 结论。
- C4 不得改父工程 root settings（单模块红线 FR-000-001）。

## 附、统一队列跑批记录（2026-11-06）

- 载体：`emulator-5554`（sdk_gphone64_x86_64 API 37 × ndk_translation；App 域 ABI=arm64）；由编排者统一排队串行执行（安装→`--ez autorun true`→`run-as cat` 回读报告）。
- 报告：`logs/20261105-1/report-node-pty.json`（工程内留档，不入库）。
- 汇总：5 探针：PASS 2 / BLOCKED 3（PRB-02/03 无匹配 runtime 注入 BLOCKED＝版本失配 22.23.4/ABI127 vs 26.13.0/ABI147 事实；PRB-05 ⬜ BLOCKED；PRB-04 PASS-FACT=版本失配锁定）。
- 判读纪律：翻译模拟器结果**仅记录**，正式 G3 需原生 arm64 复测；FAIL/BLOCKED 均按本节/Spec 既有分类如实记录，未伪造 PASS。

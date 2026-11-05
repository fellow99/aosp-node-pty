# 101-probe-app 任务拆解

> 对应：[spec.md](./spec.md) / [plan.md](./plan.md)
> 原则：spec-first；本批只到「probe-app 构建通过 + 载荷打包核验」，device 运行由编排者统一排队。
> 顺序：规范 → 载荷 → 工程 → 探针 → 构建 → 文档/日志。

## T1 规范文档（spec-first）
- [x] T1.1 `specs/101-probe-app/spec.md`（RFC2119，FR-101-PROBE-001…009，含版本配对诚实事实与 spawn ⬜）— FR-101-PROBE-*
- [x] T1.2 `specs/101-probe-app/plan.md`（真实路径、FR 交叉引用、构建命令、设计决策）
- [x] T1.3 `specs/101-probe-app/tasks.md`（本条）
- [x] T1.4 `specs/101-probe-app/test-cases.md`（PRB-xx 判据 + 实跑前置，⭐BLOCKED ⬜ 明示）

## T2 载荷获取与投放
- [x] T2.1 从本仓 GitHub Release v22.23.4 下载 `node-pty-v22.23.4-android-arm64-v8a.tar.gz`（+ .sha256）；
      校验归档 sha256 == `3a52ad91…c7e7a` — FR-101-PROBE-002
- [x] T2.2 解包取 `libpty_node.so`（sha `7f514ee5…25220`、71,120 B），核验 ELF64 AArch64 / NDK r30 /
      DT_NEEDED 含 libnode.so，投放 `probe-app/app/src/main/jniLibs/arm64-v8a/` — FR-101-PROBE-001/002
- [x] T2.3 版本配对事实记录（lock active 26.13.0/147 vs 实际 22.23.4/127 ⇒ spawn ⬜）— FR-101-PROBE-005/006

## T3 probe-app 工程（独立 Gradle，参照 aosp-node-cli/probe-app）
- [x] T3.1 `settings.gradle.kts`（rootProject.name=pty-probe-app，include(":app")）
- [x] T3.2 `build.gradle.kts`（`com.android.application` 9.2.1 apply false）
- [x] T3.3 `gradle.properties`（jvmargs / useAndroidX=false / PROBE_APP_VERSION=22.23.4）
- [x] T3.4 `local.properties`（sdk.dir；git 忽略）
- [x] T3.5 `.gitignore`（build/.gradle/local.properties/`app/src/main/jniLibs/**/*.so`）
- [x] T3.6 `app/build.gradle.kts`（namespace org.fellow99.ptyprobe，minSdk24/compileSdk36/targetSdk35，
      ABI 仅 arm64-v8a，`useLegacyPackaging=true`，Java 17）— FR-101-PROBE-001
- [x] T3.7 `app/src/main/AndroidManifest.xml`（extractNativeLibs=true，无 INTERNET）
- [x] T3.8 `app/src/main/java/org/fellow99/ptyprobe/MainActivity.java`（单一 Activity）

## T4 MainActivity 探针
- [x] T4.1 探针 runner：声明式 `Probes` + 逐探针超时（worker join 兜底）+ 三态 verdict — FR-101-PROBE-007/009
- [x] T4.2 PRB-01 载荷就位：nativeLibraryDir 存在 + size>0 + ELF64/AArch64 + sha==lock — FR-101-PROBE-001
- [x] T4.3 PRB-02 预载 libnode.so：`System.loadLibrary("node")` 成功=PASS，否则 BLOCKED:无匹配 runtime 注入 — FR-101-PROBE-003
- [x] T4.4 PRB-03 加载 addon：`System.loadLibrary("pty_node")` 成功=链接可解析；失败记录具体原因 — FR-101-PROBE-004
- [x] T4.5 PRB-04 版本记录：active(26.13.0/147) vs artifact(22.23.4/127) 差异 + 实际 sha vs lock（fact）— FR-101-PROBE-005
- [x] T4.6 PRB-05 spawn 回程 ⬜：恒 BLOCKED(⬜ 后续批次)，不执行 — FR-101-PROBE-006
- [x] T4.7 报告 `files/probe-report.json`（runAt/device/abi/expectedPairing/probes[]）+ UI log — FR-101-PROBE-007
- [x] T4.8 无头 `--ez autorun true` — FR-101-PROBE-008
- [x] T4.9 `RUN ALL PROBES` 按钮

## T5 构建与核验
- [x] T5.1 `gradle -p probe-app :app:assembleDebug --console=plain`（Gradle 9.4.1 发行版）BUILD SUCCESSFUL
- [x] T5.2 APK 路径记录 + `unzip -l` 确认 `lib/arm64-v8a/libpty_node.so` 入包（size 与源一致）
- [x] T5.3 静态核验：投放 .so sha == Release 归档 sha

## T6 日志与收尾
- [x] T6.1 `logs/20261105-1/DEV_CHECKLIST.md`
- [x] T6.2 复核：不改 git 分支、无 git add/commit、未触碰其它子工程/父工程模块

## 执行结果
- [x] 全部完成（T1–T6）；device 运行 ⬜ 由编排者统一排队（见 test-cases 实跑前置）。

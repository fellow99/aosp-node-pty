# 000-scaffold 任务拆解

> 对应：[spec.md](./spec.md) / [plan.md](./plan.md)
> 原则：M1 骨架完整；脚本与 workflow 可运行；不要求本地产出 `.so`。
> 顺序：先工程骨架 → lock/工具 → 脚本 → CI → 文档 → 校验。
>
> **执行结果（2026-10-30）：26/26 全部完成。** Code Review 修 2 个 Critical（异常宏模型、
> `NODE_GYP_MODULE_NAME`）；CI 经 5 轮真实日志修复后全绿（run `41027607245`），Release
> `v24.22.1` 已发布。详见 plan.md §9 与 `logs/20261030-1/DEV_CHECKLIST.md`。

## T1 Gradle 工程骨架
- [x] T1.1 `settings.gradle.kts`（rootName=aosp-node-pty，include :node-pty，pluginManagement 0.37.0）— FR-000-001
- [x] T1.2 `gradle/libs.versions.toml`（AGP 9.2.1）— FR-000-002
- [x] T1.3 `gradle.properties`（GROUP、PTY_VERSION=24.22.1、POM_*、RELEASE=false）— FR-000-003/004/053
- [x] T1.4 `gradle.properties.local.example`
- [x] T1.5 `publish.gradle.kts`（移植 runtime 发布面，url 改为 pty 仓，版本源 PTY_VERSION）
- [x] T1.6 `node-pty/build.gradle.kts`（android library，namespace，minSdk24，jniLibs useLegacyPackaging=false，版本覆盖）
- [x] T1.7 验收：无 `.so` 下 `gradle projects` 成功

## T2 唯一事实源
- [x] T2.1 `pty.lock.json`：active=24.22.1/arm64-v8a，draft release（nodeAbi 137，全 gates 键，占位 sha）— FR-000-010~013

## T3 投放与门禁工具
- [x] T3.1 `tools/provision.mjs`：ELF 解析 + `--check/--inspect/--out/--node/--abi`；门禁 ET_DYN/无INTERP/AArch64/**NEEDED libnode**/未定义 napi/16K/sha/size — FR-000-030~035
- [x] T3.2 仅投放 libpty_node.so；写 META-INF/node-pty.properties — FR-000-034/036
- [x] T3.3 退出码 0/2/1
- [x] T3.4 `node --check tools/provision.mjs`

## T4 构建脚本
- [x] T4.1 `scripts/fetch-node-pty-source.mjs`（取 node-pty tarball + node headers，版本参数化）— FR-000-020
- [x] T4.2 `scripts/build-pty-android.sh`（clang++ 编 pty.cc，六坑固化，strip，元数据，参数化 --node/--api/--src/--addon/--headers/--libnode/--out/--ndk）— FR-000-021~023
- [x] T4.3 `node --check` / `bash -n` 语法校验

## T5 GitHub Actions
- [x] T5.1 `.github/workflows/build-pty-android.yml`：**tag `v*` push + workflow_dispatch 双通道**；版本解析 step 写 `$GITHUB_ENV`；NDK r28c；匿名 curl 拉 runtime 同版 public Release 资产并按 hash 校 sha（失败即停）；取头/node-pty 1.1.0/addon-api 7.1.1；编译→strip→verify→provision 门禁→tar.gz；artifact + Release（clobber、tag 钉 commit、prerelease）— FR-000-040~044

## T6 卫生 / 许可 / 文档
- [x] T6.1 重写 `.gitignore`（忽略 jniLibs 的 .so、.dist、构建目录，保留非二进制）
- [x] T6.2 `node-pty/THIRD-PARTY-NOTICES.md`
- [x] T6.3 `README.md` + `README_zh.md`（定位/坐标/配对/AAR/构建/门禁/加载适配契约）

## T7 规格文档与日志
- [x] T7.1 `specs/000-scaffold/{spec,plan,tasks,test-cases}.md`
- [x] T7.2 `logs/20261030-1/DEV_CHECKLIST.md`

## T8 验证
- [x] T8.1 `gradle projects`/`help` 配置期通过
- [x] T8.2 provision 负向样本退出码 2；`node --check`；`bash -n`
- [x] T8.3 Code review（requesting + receiving）→ REVIEW_REPORT.md，修 Critical/Important
- [x] T8.4 提交（Conventional Commits 前缀）
- [x] T8.5 打 tag `v24.22.1` 推送触发 workflow，观察执行；失败则修复并复跑

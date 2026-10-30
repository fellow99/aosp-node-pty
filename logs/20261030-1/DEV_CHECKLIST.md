# DEV_CHECKLIST — 000-scaffold 工程搭建

> 日期：2026-10-30 · 工程：aosp-node-pty · 分支：main（不改动）· 目标：M1 骨架完整，不本地产 .so

## 进度
- [x] T1 Gradle 骨架（settings/libs/properties/publish/模块 build）
- [x] T2 pty.lock.json
- [x] T3 tools/provision.mjs（含 neededLibnode + 未定义 napi 门禁）
- [x] T4 scripts（fetch mjs + build sh，五坑固化）
- [x] T5 .github/workflows/build-pty-android.yml
- [x] T6 .gitignore / THIRD-PARTY / README(中英)
- [x] T7 specs + logs
- [x] T8 校验（gradle 配置期 / node --check / bash -n / provision 负向样本）
- [x] Code review + 修复
- [ ] 提交 + tag v24.22.1 触发 CI + 观察/修复

## Code Review 结论（d152f43..HEAD，2026-10-30）
以 node-pty@1.1.0 实际 npm 源为证据核对：
- **Critical-1（已修）**：脚本初版用 `-DNAPI_DISABLE_CPP_EXCEPTIONS`，但 node-pty 的 binding.gyp
  依赖 `node_addon_api_except`（定义 NAPI_CPP_EXCEPTIONS），pty.cc 为裸 `throw Napi::Error`。
  已改为 `-fexceptions -DNAPI_CPP_EXCEPTIONS`（规划 §3.3 原宏假设有误，以源码为准）。
- **Critical-2（已修）**：pty.cc 末尾 `NODE_API_MODULE(NODE_GYP_MODULE_NAME, init)` 需要宏；
  已加 `-DNODE_GYP_MODULE_NAME=pty_node`，并显式 `-DNAPI_VERSION=9`。
- **Minor（已修）**：provision 门禁⑧失败文案写死“4KB”，改为显示实际 min LOAD align。
- 同步更正 spec FR-000-021/022、TC-32、README 中英。
- 证据：node-pty 单文件 src/unix/pty.cc（__linux__ 用 <pty.h>，bionic libc 提供 forkpty，不链 -lutil）；
  node-addon-api 实际解析 7.1.1；runtime Release 资产路径为 ./bin/arm64-v8a/libnode.so（find 可命中）。

## 本地验证结果（2026-10-30）
- node --check provision.mjs / fetch mjs：通过（修复一处块注释内 `*/` 误闭合）
- bash -n build-pty-android.sh：通过
- pty.lock.json JSON 合法；workflow 四输入齐全
- provision：libnode.so 负向样本 gate④⑤ 失败 exit=2；--out 拒绝=2；用法错误=1；未知 release=1；空 --check=2
- gitignore：jniLibs 下 .so 被忽略
- gradle projects：BUILD SUCCESSFUL，:node-pty 在位；version=24.22.1；publishToMavenCentral/sign 任务存在（RELEASE=false 默认禁用执行）
- 坐标 groupId 由 vanniktech coordinates 固定 io.github.fellow99（与 runtime 同，project.group 无关）

## 关键决策
- 版本源唯一 PTY_VERSION=24.22.1（禁用 NODE_ 前缀）。
- provision 与 runtime 同 ELF 解析，但 gate④=NEEDED libnode.so、gate⑤=未定义 napi_*；投放只复制一个 .so。
- CI 链接用 libnode.so 取自 aosp-node-runtime v24.22.1 Release，失败即停。

## 问题与修复
（开发中记录）

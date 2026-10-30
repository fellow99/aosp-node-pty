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
- [ ] Code review + 修复
- [ ] 提交 + tag v24.22.1 触发 CI + 观察/修复

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

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
- [x] 提交 + tag v24.22.1 触发 CI + 观察/修复

## CI 联调结果（tag v24.22.1，最终绿 run=41027607245）
逐次失败→定位→修复（均看真实日志，非猜）：
1. 加载期失败（job env 前向引用 steps.ver.outputs）→ 改为版本解析 step 写 $GITHUB_ENV。
2. step7 跨仓 `gh release download` 无 token 权限 → runtime 为 public，改匿名 curl 拉 browser_download_url。
3. step7 sha256sum -c：runtime 的 .sha256 文件名字段带紧贴的 '*' → 取 hash 自行比对。
4. step7 `NODE_VERSION: unbound variable`：$GITHUB_ENV 写成小写 node/pty/api → 统一大写 NODE_VERSION 等。
5. step9 exit 126 Permission denied（脚本未加可执行位）→ CI `bash scripts/...` 且 git update-index --chmod=+x。
最终产物（全部门禁过）：
- ELF64 AArch64 ET_DYN, stripped；DT_NEEDED 含 libnode.so（另有 libc++_shared/libm/libdl/libc）。
- napi_* 为未定义 U 符号（装载期由引擎解析）；16KB 页对齐；无 PT_INTERP。
- libpty_node.so size=70896（unstripped 96848）sha256=c65fd205…dfa54；NDK 28.2.13676358；node-addon-api 7.1.1。
- GitHub Release v24.22.1（非 draft/prerelease）：tar.gz + .sha256（包 sha a8a9c0e4…），内容仅
  libpty_node.so + BUILD-METADATA.txt + FETCH-METADATA.json；artifact 同名在 Actions。
- 已把产物事实回写 pty.lock.json（status=released）。Maven Central 未发布（本需求不涉及，published=false）。

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

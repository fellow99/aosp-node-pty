# 000-scaffold 技术方案（As-Built）

> 模块：000-scaffold
> 对应规格：[spec.md](./spec.md)
> 最后更新：2026-10-30
> 范围：M1 骨架完整（脚本可运行、workflow 完整），不要求本地产出 `.so`；交叉编译正确性在 CI 验证。

## 1. 技术上下文

### 1.1 运行环境

| 项 | 值 |
|---|---|
| Gradle | 9.4.x（与 runtime 对齐；本地 sdkman 9.4.1 验证配置） |
| AGP | 9.2.1（`gradle/libs.versions.toml`） |
| 发布插件 | com.vanniktech:gradle-maven-publish-plugin 0.37.0（经 settings pluginManagement） |
| compileSdk / minSdk | 36 / 24 |
| ABI | arm64-v8a（唯一） |
| NDK（CI） | r28c `28.2.13676358`，`aarch64-linux-android24-clang++` |
| Node（工具运行时） | 任意支持 ESM 的 Node（脚本为 `.mjs`） |
| 制品 | AAR，内含且仅含 `jni/arm64-v8a/libpty_node.so` |

### 1.2 依赖（直接 / 间接）

- `com.android.library`（构建 AAR 容器，无 Kotlin/Java 源）。
- `com.vanniktech.maven.publish.base`（发布面，默认禁用）。
- 上游构建输入（非 Gradle 依赖，CI/脚本获取）：node-pty npm tarball、node-addon-api（随 node-pty 依赖固定版本）、
  Node 头文件、配对 `libnode.so`（仅链接）。

## 2. 与规划基线/宪法的合规

- ✅ 版本恒等于 Node 版本：`PTY_VERSION=24.22.1`（FR-000-003）。
- ✅ 不使用 `NODE_` 前缀属性（FR-000-004），镜像 runtime 对 Gradle 9.4 保留名的规避。
- ✅ 二进制不入库：`.gitignore` 忽略 jniLibs 下 `.so`（FR-000-050）。
- ✅ 单一事实源 `pty.lock.json`，版本+配对+门禁+出处（FR-000-010~013）。
- ✅ 发布卫生：`RELEASE=false` 默认，凭据仅用户级（FR-000-053）。
- ✅ 结构镜像 runtime：模块名 `node-pty` 对应 `node-runtime`，工具/脚本/workflow 命名同口径。

## 3. 关键设计决策与理由

### 3.1 为什么产物只放一个 libpty_node.so

规划 §3.1：`libnode.so` 与 `libc++_shared.so` 由 runtime/统一供给；pty 重复携带会在同一 APK
造成双引擎/双 C++ 运行时。故 provision 的投放逻辑**刻意不复制**任何伴生库（与 runtime provision 相反）。

### 3.2 为什么必须 DT_NEEDED libnode.so（不可妥协门禁）

宿主常以 `RTLD_LOCAL` 加载 `libnode.so`，普通 `dlopen` 解析不到其中的 `napi_*`/`node_*`。
链接期加 `-Wl,--no-as-needed -l:libnode.so` 让产物在动态表登记 soname 依赖，运行期由动态链接器
按 soname 复用已加载引擎。这是 pty 相对 runtime 新增的硬门禁 `neededLibnode`。

### 3.3 为什么 napi_* 必须是"未定义引用"

pty 不静态链接 NAPI 实现；`napi_*` 出现在 `.dynsym` 的未定义符号中，由引擎在装载时解析。
provision 读取动态符号表，断言存在未定义的 `napi_*`（`undefinedNapiResolvable`）。

### 3.4 为什么 CI 必须先取配对 libnode.so

NODE_MODULE_VERSION 由头文件版本决定，而链接期还需一份同版 `libnode.so` 解析符号。
CI 只信任来自 `aosp-node-runtime` 同版本 Release 的资产；缺失/不匹配即失败，杜绝跨版本误编。

### 3.5 版本属性与坐标覆盖

沿用 runtime 的发布反射方案：模块 `build.gradle.kts` 读 `PTY_VERSION` 设 `project.version`，
在 `afterEvaluate` 反射覆盖各 publication 的 version；`publish.gradle.kts` 用
`coordinates("io.github.fellow99", project.name, versionName)`。pty 侧版本源唯一为 `PTY_VERSION`。

## 4. 数据模型（pty.lock.json）

根：`{ $comment, active:{node,abi}, releases:[ReleaseRecord], matrix:{minSdk,abi,note} }`。

`ReleaseRecord`（实现 FR-000-011/012）：

```jsonc
{
  "node": "24.22.1", "nodeAbi": 137, "abi": "arm64-v8a", "androidApi": 24,
  "name": "libpty_node.so", "size": 0, "sha256": "",
  "pairedRuntime": { "coordinates": "io.github.fellow99:node-runtime:24.22.1",
                     "releaseTag": "v24.22.1", "libnodeSoname": "libnode.so" },
  "builtFrom": { "nodePty": "...", "nodeHeaders": "...", "nodeAddonApi": "...",
                 "ndk": "r28c (28.2.13676358)", "flags": "...", "stripped": "llvm-strip --strip-unneeded" },
  "gates": { "eTypeEtDyn": true, "ptInterp": 0, "eMachineAArch64": true,
             "neededLibnode": true, "undefinedNapiResolvable": true,
             "sha256Match": true, "sizeMatch": true, "pageAlign16k": true },
  "build": 0,
  "release": { "tag": "v24.22.1", "url": "", "archive": "node-pty-v24.22.1-android-arm64-v8a.tar.gz" },
  "mavenCentral": { "coordinates": "io.github.fellow99:node-pty:24.22.1", "published": false },
  "status": "draft"
}
```

> draft 阶段 `size=0`、`sha256=""`；CI 首次成功后回写真实值并把门禁/状态置真。

## 5. 接口契约

### 5.1 provision.mjs CLI（提供）

| 用法 | 行为 | 退出码 |
|---|---|---|
| `--check` | 校验已投放 `node-pty/src/main/jniLibs/<abi>/libpty_node.so` | 0/2/1 |
| `--inspect <so>` | 打印门禁报告，不写文件 | 0/2/1 |
| `--out <dir> --from <so>` | 过门后投放单个 `.so` 并写 properties | 0/2/1 |
| `--node <v> --abi <a>` | 选择 lock 中 release（默认 active） | — |

### 5.2 build-pty-android.sh 参数（提供）

`--node <ver> --api <24> --libnode <path-to/libnode.so> --out <libpty_node.so>`，
另需环境 `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT` 或 `--ndk <dir>`，源目录通过 `--src <node-pty>` 或环境。

### 5.3 fetch-node-pty-source.mjs（提供）

拉取 node-pty npm tarball（不改源）与 node 头文件，校验版本，输出到工作目录。

### 5.4 workflow 输入（提供给 CI 使用者）

`node_version`(默认 24.22.1)、`android_api`(24)、`node_pty_version`(固定上游钉选)、`prerelease`。

## 6. 实现策略

### 6.1 目录结构（落地）

```
aosp-node-pty/
├── settings.gradle.kts
├── gradle.properties
├── gradle.properties.local.example
├── gradle/libs.versions.toml
├── publish.gradle.kts
├── pty.lock.json
├── node-pty/
│   ├── build.gradle.kts
│   ├── THIRD-PARTY-NOTICES.md
│   └── src/main/jniLibs/arm64-v8a/   # .so 投放处（git 忽略）
├── scripts/
│   ├── fetch-node-pty-source.mjs
│   └── build-pty-android.sh
├── tools/provision.mjs
├── .github/workflows/build-pty-android.yml
├── README.md / README_zh.md
└── specs/000-scaffold/{spec,plan,tasks,test-cases}.md
```

### 6.2 provision 门禁算法

复用 runtime 验证过的极简 ELF64/小端解析：读 ELF 头（e_type/e_machine/程序头）、`.dynamic`
（NEEDED/SONAME）、`.dynsym`（未定义符号）。pty 差异点：
- gate④ 改为 **NEEDED 含 libnode.so**；
- 删除 runtime 的 `node::Start` 定义检查，替换为 **存在未定义 `napi_*`** 检查；
- 投放只复制一个文件，不查 libc++。

### 6.3 错误处理

- provision：解析失败→捕获→`{ok:false, gate:'parse'}`；CLI 顶层 catch→退出码 1；门禁不过→2。
- build 脚本：`set -euo pipefail`；缺 NDK/clang/--libnode/源文件即非零退出并打印原因。
- CI：任一步骤失败即整体失败；Release 已存在则 clobber 资产并把 tag 钉到本次 commit。

## 7. 测试考量（000 可在本地/CI 验证）

- Gradle 配置期：`gradle projects` / `help` 在无 `.so` 下成功（FR-000-005）。
- provision 负向：构造/取一个无 `DT_NEEDED libnode.so` 的 ELF，断言退出码 2。
- provision 正向逻辑：用任意 AArch64 ET_DYN 小样本验证解析分支（真正 pty `.so` 由 CI 产出后回归）。
- `node --check` 对两个 `.mjs` 做语法校验；`bash -n` 校验 shell 脚本。
- workflow YAML 结构与输入完整性静态检查。
- 端到端：打 tag 触发 CI，观察是否取到 runtime `libnode.so`、编译过门、发布 Release。

## 8. 文件清单

| 文件 | 用途 | 对应 FR |
|---|---|---|
| `settings.gradle.kts` | root 名 + include `:node-pty` + 插件/仓库 | 001 |
| `gradle.properties` | GROUP/PTY_VERSION=24.22.1/POM/RELEASE=false | 003/004/053 |
| `gradle/libs.versions.toml` | AGP 版本目录 | 002 |
| `publish.gradle.kts` | vanniktech 发布面（默认禁用） | 003/053 |
| `node-pty/build.gradle.kts` | Android library + 坐标版本覆盖 | 002/003 |
| `pty.lock.json` | 唯一事实源 | 010-013 |
| `scripts/fetch-node-pty-source.mjs` | 取 node-pty 源 + Node 头 | 020 |
| `scripts/build-pty-android.sh` | NDK 交叉编译（固化五坑） | 021-023 |
| `tools/provision.mjs` | 投放 + ELF/DT_NEEDED 门禁 | 030-036 |
| `.github/workflows/build-pty-android.yml` | CI + Release | 040-044 |
| `.gitignore` | 二进制/构建产物忽略 | 050 |
| `node-pty/THIRD-PARTY-NOTICES.md` | 第三方许可 | 051 |
| `README.md`/`README_zh.md` | 消费契约与构建说明 | 052 |

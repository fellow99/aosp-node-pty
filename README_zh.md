# aosp-node-pty

[**English**](README.md) · [中文](README_zh.md)

**以 AAR 形式发布的、Android 版 [node-pty](https://www.npmjs.com/package/node-pty) 原生件**——一份交叉编译的
`libpty_node.so`（arm64-v8a），由应用进程内的 Node 运行时（[`aosp-node-runtime`](https://github.com/fellow99/aosp-node-runtime)
的 `libnode.so`）经 N-API 加载，提供伪终端（`forkpty`/`openpty`）能力。

本仓库**不含任何嵌入 API，也不含 JS 运行时封装**——只有原生二进制的可复现构建、ELF 门禁与发布流水线。
它是 `aosp-node-runtime` 的严格**伴生制品（companion artifact）**。规划基线见
[`docs/000-工程规划.md`](docs/000-工程规划.md)。

## 版本：恒等于 Node 版本

制品版本**始终等于**所配对的 Node.js / `aosp-node-runtime` 版本，不引入独立 semver。

| 运行时 | pty（本仓） |
|---|---|
| `io.github.fellow99:node-runtime:24.22.1` | `io.github.fellow99:node-pty:24.22.1` |

明确**不保证**跨 Node 大版本兼容（NODE_MODULE_VERSION 不同）。两个依赖必须解析到完全相同的版本。
当前版本：`24.22.1`（NODE_MODULE_VERSION 137）。

## 用法

```kotlin
// build.gradle.kts
dependencies {
    val nodeVer = "24.22.1" // runtime 与 pty 必须同版本
    runtimeOnly("io.github.fellow99:node-runtime:$nodeVer")
    runtimeOnly("io.github.fellow99:node-pty:$nodeVer")
}
```

APK 解包后二者同在 `nativeLibraryDir`：`libnode.so` 与 `libpty_node.so`。pty 的 AAR
**只含** `libpty_node.so`，绝不携带 `libnode.so` 或 `libc++_shared.so`。

## 加载契约

AAR 把 `libpty_node.so` 投放到只读 `nativeLibraryDir`（`useLegacyPackaging=false` 时唯一合规的可执行位置）。
上游 node-pty 的 JS 默认按 `build/Release/pty.node` 定位原生件，因此消费方必须在 `require('node-pty')`
**之前**安装定位适配，例如：

```kotlin
// 首选：以 soname 经应用 linker namespace 预加载，随后让 node-pty 的原生绑定命中已加载库。
System.loadLibrary("pty_node")
```

……或在 Node 侧覆盖 node-pty 的原生件解析路径，指向由宿主桥注入的
`<nativeLibraryDir>/libpty_node.so` 绝对路径。加载成功后即可 `forkpty`
（`unlockpt/grantpt → setsid → TIOCSCTTY → exec shell`，由 node-pty 完成）。

原生件缺失、ABI 不符或 SELinux 拒绝时，消费方应**结构化上报**，绝不能静默回退到假终端。

## AAR 内容

```
jni/arm64-v8a/libpty_node.so    # node-pty N-API 原生件（ET_DYN，无 PT_INTERP，DT_NEEDED libnode.so）
```

要求：`minSdk 24` · ABI `arm64-v8a`。

## 如何构建

二进制**不入库**，也从不由手工构建。[`build-pty-android.yml`](.github/workflows/build-pty-android.yml) 在 GitHub Actions 上：

1. 从匹配版本的 `aosp-node-runtime` GitHub Release 下载配对的 `libnode.so`，**仅用于链接期**；
   若该版本不存在则直接失败。
2. 经 [`scripts/fetch-node-pty-source.mjs`](scripts/fetch-node-pty-source.mjs) 取**未修改**的 node-pty npm 源、
   匹配版本的 `node-v<version>-headers.tar.gz` 与 `node-addon-api`。
3. 经 [`scripts/build-pty-android.sh`](scripts/build-pty-android.sh) 用 Android NDK r28c 交叉编译
   `src/unix/pty.cc`：
   - `-fexceptions -DNAPI_CPP_EXCEPTIONS`，与 node-pty 上游 `node_addon_api_except` 依赖一致（不用 `NAPI_DISABLE_CPP_EXCEPTIONS`，不定义 `NODE_ADDON_API_ENABLE_MAYBE`）；
   - `-Wl,--no-as-needed -l:libnode.so` 使产物登记 `DT_NEEDED libnode.so`；
   - 输出命名 `libpty_node.so`；不链 `-lutil`（bionic 在 libc 提供 `forkpty`）。
4. `llvm-strip --strip-unneeded`，随后运行 ELF 门禁。
5. 发布 `node-pty-v<version>-android-arm64-v8a.tar.gz` 到 GitHub Release `v<version>`。

手动触发：

```bash
gh workflow run build-pty-android.yml -f node_version=24.22.1 -f node_pty_version=1.1.0 -f android_api=24
```

## 锁定与校验

[`pty.lock.json`](pty.lock.json) 是配对 Node 版本、NODE_MODULE_VERSION、二进制 SHA-256、门禁与出处的唯一事实源。

```bash
node tools/provision.mjs --check          # 按 lock 校验已投放的 libpty_node.so
node tools/provision.mjs --inspect <so>   # 打印某个二进制的门禁报告
node tools/provision.mjs --out node-pty/src/main/jniLibs --from <so>
```

退出码：`0` 全过 · `2` 门禁不合格（拒绝投放）· `1` 用法/IO 错误。

门禁包含：`ET_DYN`、无 `PT_INTERP`、AArch64、**`DT_NEEDED` 含 `libnode.so`**、
`napi_*` 为未定义符号（装载时由引擎解析）、16KB 页对齐、与 lock 的 sha/size 一致。

## 仓库结构

```
aosp-node-pty/
├── node-pty/                      # Android library 模块（AAR 容器）
│   └── src/main/jniLibs/          # libpty_node.so 投放处（git 忽略；CI/provision）
├── scripts/
│   ├── fetch-node-pty-source.mjs  # node-pty npm 源 + Node 头文件 + node-addon-api
│   └── build-pty-android.sh       # NDK 交叉编译（固化五条踩坑）
├── tools/provision.mjs            # 投放 + ELF / DT_NEEDED 门禁
├── pty.lock.json                  # 配对版本 + SHA-256 + 门禁 + 出处
└── .github/workflows/build-pty-android.yml
```

## 许可

[MIT](LICENSE)。node-pty（MIT，Microsoft）、node-addon-api（MIT）、Node-API/Node.js（MIT）——
见 [`node-pty/THIRD-PARTY-NOTICES.md`](node-pty/THIRD-PARTY-NOTICES.md)。

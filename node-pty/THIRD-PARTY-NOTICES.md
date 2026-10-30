# node-pty — 第三方组件与许可

本模块（`:node-pty`）只承载 **node-pty 原生件**（`libpty_node.so`），不含本仓库自有的
Kotlin/Java 可执行代码；AAR 内**仅有** `jni/arm64-v8a/libpty_node.so`。

## 产物构成

| 项 | 值 |
|---|---|
| 文件 | `libpty_node.so`（`arm64-v8a`） |
| 性质 | node-pty UNIX 目标 `src/unix/pty.cc` 的 N-API 交叉编译共享库（源码**不改**） |
| 链接引擎 | 链接期 `-l:libnode.so` 仅作符号解析；**不打包** `libnode.so` / `libc++_shared.so` |
| 运行期依赖 | 由消费方 APK 提供同版本 `io.github.fellow99:node-runtime:<node>`（`libnode.so`） |
| 版本 | 制品版本恒等于配对 Node 版本（如 `24.22.1`）；node-pty 上游版本记录于 `pty.lock.json` 的 `builtFrom.nodePty` |

## 许可与归属

- **[node-pty](https://www.npmjs.com/package/node-pty)**：Copyright (c) Microsoft Corporation 及 contributors，
  **MIT License**。上游源码以 npm 包形式获取、不做修改（见 `scripts/fetch-node-pty-source.mjs`）。
  node-pty 自身整合了来自 [libuv](https://libuv.org/) 与其它项目的代码，其归属与许可见 node-pty
  上游包内的 `LICENSE` / 源码版权头。
- **[node-addon-api](https://www.npmjs.com/package/node-addon-api)**：Node.js 团队，**MIT License**。
  仅使用其 C++ 头文件（`napi.h` 等），随 node-pty 依赖固定版本；以头文件方式参与编译，不单独形成动态库。
- **Node-API（N-API）**：`napi_*` 接口与 `node_api.h` 等头文件属于 **Node.js**（MIT）；
  实现位于运行期的 `libnode.so`（由 `aosp-node-runtime` 提供），**不编入**本产物。
- **Android NDK r28c / bionic libc**：`forkpty`/`openpty` 在 bionic libc（Android 平台，无独立 libutil）；
  NDK 自带组件遵循其随附许可（LLVM/Apache-2.0 等）。本产物不重分发 NDK 库
  （`libc++_shared.so` 由 node-runtime 统一供给）。

> 发布到 Maven Central 前，需确保随产物/发布材料提供上述 MIT 许可全文（node-pty、node-addon-api、Node.js）。

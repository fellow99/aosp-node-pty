# 000-scaffold 测试用例

> 范围：工程骨架可在本地静态/配置期验证；真正 `libpty_node.so` 的交叉编译与 ELF 门禁在 CI 端到端验证
> （本地仅 NDK r27.2，不要求产出 .so）。

## TC-GRADLE 配置期

| ID | 用例 | 步骤 | 期望 |
|---|---|---|---|
| TC-01 | 无 .so 可配置 | 干净检出后 `gradle projects` | BUILD SUCCESSFUL；列出 `:node-pty` |
| TC-02 | 坐标版本 | 读 `gradle.properties` + 模块构建脚本 | PTY_VERSION=24.22.1，version 来源于该属性 |
| TC-03 | 无 NODE_ 前缀 | grep `NODE_` 于 gradle 配置 | 不出现版本属性 NODE_* |
| TC-04 | 默认不发布 | grep RELEASE | `RELEASE=false` |

## TC-LOCK 事实源

| ID | 用例 | 期望 |
|---|---|---|
| TC-10 | active | node=24.22.1, abi=arm64-v8a |
| TC-11 | draft 完整性 | 含 nodeAbi=137、pairedRuntime 坐标、全 gates 键；size=0/sha="" /status=draft |
| TC-12 | JSON 合法 | `node -e "JSON.parse(...)"` 无异常 |

## TC-PROVISION 门禁（tools/provision.mjs）

| ID | 用例 | 步骤 | 期望退出码 |
|---|---|---|---|
| TC-20 | 用法错误 | 无参数且无已投放物 / `--out` 无 `--from` | 1 |
| TC-21 | 缺引擎绑定拒绝 | `--inspect <ELF without NEEDED libnode.so>` | 2，`neededLibnode` 失败 |
| TC-22 | draft sha 不阻塞 | 合格 ELF 在 draft lock 下 inspect | sha/size 仅记录，硬门禁照常 |
| TC-23 | 投放只放一个 | `--out … --from <so>` 成功 | 目标仅 `libpty_node.so`，无 libnode/libc++ |
| TC-24 | properties 生成 | 投放后读 META-INF/node-pty.properties | 含 node/abi/sha256 字段 |
| TC-25 | 语法 | `node --check tools/provision.mjs`` | 0 |

## TC-BUILD 脚本

| ID | 用例 | 期望 |
|---|---|---|
| TC-30 | shell 语法 | `bash -n scripts/build-pty-android.sh` 退出 0 |
| TC-31 | mjs 语法 | `node --check scripts/fetch-node-pty-source.mjs` 退出 0 |
| TC-32 | 编译参数固化 | grep：有 `-fexceptions -DNAPI_CPP_EXCEPTIONS`；无 `NAPI_DISABLE_CPP_EXCEPTIONS`；无 `ENABLE_MAYBE`；有 `NODE_GYP_MODULE_NAME`；有 `--no-as-needed` 与 `-l:libnode.so`；输出名 libpty_node.so；无 `-lutil` |

## TC-CI 工作流

| ID | 用例 | 期望 |
|---|---|---|
| TC-40 | YAML 可解析 | 解析无异常；含 tag `v*` push 与 workflow_dispatch 双触发，四个 dispatch 输入齐全 |
| TC-41 | 取配对引擎 | 步骤匿名 curl 从 aosp-node-runtime v24.22.1 **public** Release 取 `bin/arm64-v8a/libnode.so`，按 hash 校 sha，失败即停 |
| TC-42 | 门禁在 CI | 调 provision --inspect，不过则失败 |
| TC-43 | 发布 | 产出 `node-pty-v24.22.1-android-arm64-v8a.tar.gz` 并发 Release v24.22.1 |

## TC-CI 工作流 —— 实测结果（run 41027607245，全绿）

| ID | 结果 | 证据 |
|---|---|---|
| TC-40 | ✅ | 版本解析 step 写大写变量到 `$GITHUB_ENV`；job env 不前向引用 step outputs |
| TC-41 | ✅ | 匿名 curl 下载成功；sha 取 hash 自行比对（runtime 文件名带紧贴 `*`，不能 `sha256sum -c`） |
| TC-42 | ✅ | provision 8 项门禁全过 |
| TC-43 | ✅ | Release v24.22.1 含 tar.gz(29,932B)+.sha256；artifact 同名上传 |

## TC-E2E 端到端（打 tag 后）

| ID | 用例 | 期望 |
|---|---|---|
| TC-50 | tag 触发 | push tag v24.22.1 后 workflow 启动 |
| TC-51 | 全链路 | 取引擎→编译→strip→全门禁过→产物上传→Release 可见 |
| TC-52 | 回归 | 若失败，修复后重跑直至绿；产物 NEEDED 含 libnode.so |

## TC-E2E —— 实测结果

| ID | 结果 | 证据 |
|---|---|---|
| TC-50 | ✅ | 强推 tag 到修复提交即触发 run 41027607245（event=push, ref=v24.22.1） |
| TC-51 | ✅ | 14 步全成功；产物 ELF64 AArch64 ET_DYN stripped，`DT_NEEDED` 含 libnode.so，`napi_*` 为 U，16KB 对齐 |
| TC-52 | ✅ | 历经 5 轮修复（job env 前向引用 / gh 跨仓权限 / sha 文件名 / 变量大小写 / exit126 可执行位）后转绿 |

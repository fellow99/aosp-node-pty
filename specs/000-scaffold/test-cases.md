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
| TC-32 | 五坑固化 | grep：无 ENABLE_MAYBE；有 `--no-as-needed` 与 `-l:libnode.so`；输出名 libpty_node.so；无 `-lutil` |

## TC-CI 工作流

| ID | 用例 | 期望 |
|---|---|---|
| TC-40 | YAML 可解析 | 解析无异常；含 workflow_dispatch 与四个输入 |
| TC-41 | 取配对引擎 | 步骤从 aosp-node-runtime v24.22.1 Release 取 libnode.so，失败即停 |
| TC-42 | 门禁在 CI | 调 provision --inspect/--check，不过则失败 |
| TC-43 | 发布 | 产出 `node-pty-v24.22.1-android-arm64-v8a.tar.gz` 并发 Release v24.22.1 |

## TC-E2E 端到端（打 tag 后）

| ID | 用例 | 期望 |
|---|---|---|
| TC-50 | tag 触发 | push tag v24.22.1 后 workflow 启动 |
| TC-51 | 全链路 | 取引擎→编译→strip→全门禁过→产物上传→Release 可见 |
| TC-52 | 回归 | 若失败，修复后重跑直至绿；产物 NEEDED 含 libnode.so |

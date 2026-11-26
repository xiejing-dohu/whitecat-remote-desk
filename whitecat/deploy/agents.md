# 注册和运行执行 Agent

每台白猫服务器维护自己的执行 Agent 名单。一个执行 Agent 是持有独立凭据、运行在工作站上的 worker。它可以承接多个独立聊天。聊天仍可选择角色和角色指令。同一位执行 Agent 可以运行不同角色。

## 在服务器登记

服务器升级时会从原有 worker.key 注册内置执行 Agent（ID 为 default），并把已有聊天归到 default。保留原有数据目录、凭据和 spool，升级后的默认 worker 可以继续接单。新建白猫实例时使用独立的 WHITECAT_STATE 目录。

在服务器本机打开白猫 Web 管理页，在侧栏点「设置」，输入执行 Agent 的 ID 和显示名称。ID 由小写字母开头，可以包含小写字母、数字、下划线和连字符，长度为 1 至 48 个字符。也可以在服务端的 whitecat 目录运行以下命令。命令需要与服务进程使用同一个 WHITECAT_STATE。

```sh
python -B executor_admin.py add --id review --name "复核助手"
python -B executor_admin.py list
```

注册会即时生效。默认配置保存在服务端数据目录的 executors/review 下。命令和本机管理页仅返回 ID、显示名称和 station.json 的位置。worker.key 存在私有目录内，服务端 SQLite 只保存随机工作站凭据的 SHA-256 摘要。在 POSIX 系统目录权限为 0700，文件为 0600。在 Windows 上注册程序为私有目录设置当前账号的 NTFS ACL，文件继承其权限。

管理员也可通过参数指定新目录及工作站使用的回环或 SSH 转发地址。已有目录与已有 ID 会报冲突。

```sh
python -B executor_admin.py add --id research --name "研究助手" \
  --config-dir /private/whitecat/research --server http://127.0.0.1:18745
```

## 启动工作站

在同机部署时，使用已登记执行 Agent 的 station.json 直接启动 worker。跨机部署时，通过受信任的运维通道把 station.json 和 worker.key 交给运行该 Agent 的工作站账号。接收端同样应限制凭据目录和文件权限。配置内的 key_file 和 spool 均相对 station.json 解析。各执行 Agent 使用各自的配置目录和 spool。运行第二个进程时须使用第二份配置。

```sh
python -B worker.py --config /private/whitecat/research/station.json
```

Windows 上相同命令可使用带引号的配置文件绝对路径。工作站需要 Python、已登录的 Codex CLI，以及连接白猫服务器的本机通道。运行 worker 的系统账号决定 Codex 能访问的工作目录与凭据。需要操作系统级隔离时，分别使用独立系统账号和 CODEX_HOME。

额外执行 Agent 只同步自己绑定的原生线程。default 保留既有原生 CLI 发现行为。需要让默认工作站也只跟踪白猫已绑定聊天时，在其 station.json 中加入 "native_discovery": false，或启动时加入 --no-native-discovery。

## 远程工作站专用入口

如果把服务通过 SSH 转发给其他工作站，服务端可同时启动仅供 worker 请求的回环监听端口。

```sh
python -B server.py --port 18742 --worker-port 18745
```

18742 供受信任的本机 Web 管理端和移动网关使用；18745 仅接受带工作站凭据的 worker 请求。在该启动方式下，从 Web 新建的 Agent 配置自动使用 worker 专用端口。远程工作站建立 SSH 本地转发后，令自己的 station.json 指向转发入口，例如 http://127.0.0.1:18745。给受限远程工作站的 SSH 凭据仅放行专用目标端口。手机使用已配对的移动网关。

## 路由和兼容性

- GET /api/executors 返回服务器实例标识和执行 Agent 的 ID、名称、在线状态及最近联系时间。GET /api/state 同时包含 executors 数组，所有会话均携带 executor 和 executor_name。
- 新建聊天时在 POST /api/jobs 中传入 executor。省略时使用 default。既有聊天续问可省略 executor，服务按原归属派发。请求指定不同执行 Agent 时返回 HTTP 409。角色仍使用 agent 字段。
- 一个命令编号可以用原参数安全重试。改变执行 Agent 再使用同一个编号会收到 HTTP 409。指定的执行 Agent 离线时，任务保持排队，直到这个 Agent 上线领取。
- 每条聊天的 SSE 和详情接口返回所属执行 Agent。流中的 online 表示该聊天的执行 Agent 是否在线。服务端根据凭据授权领取、回执、历史、原生绑定及实时数据上报。
- 配对移动网关提供只读执行 Agent 名单。新增执行 Agent 使用服务器本机的 POST /api/admin/executors，提交 id 与 name。服务器在自己的私有目录生成配置。

## 在隔离服务上验收

下列命令使用临时 SQLite 数据库、临时凭据和随机回环端口。第一项验证两份凭据的真实 HTTP 路由、跨 Agent 请求拒绝、同一 Agent 的两个聊天，以及角色和重试兼容性。第二项启动两个真实 worker.py 进程，分别使用独立的空白测试 Codex 目录，验证上线与离线队列。

```sh
python -B verify_executors.py
python -B verify_executor_workers.py
```

有已登录的专用 Codex 测试目录时，可在实际工作站运行模型问答验收。请指定为验收准备的目录。脚本创建自己的任务服务和两个 worker。它会建立三个真实 Codex 线程，并把原生历史留在指定测试目录，临时白猫数据库与 spool 在结束时清理。

```sh
python -B verify_executor_workers.py --live-home /private/whitecat-test/codex-home
```

验收中 review 承接两个聊天，分别回答 27 和 38，default 的聊天回答 49。回到 review 的第一条聊天续问，回答应为 28，另一条聊天仍只有一轮。整个过程不连接已部署白猫实例。

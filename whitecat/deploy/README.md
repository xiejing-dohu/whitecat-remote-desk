# 工作站 daemon、App 配置与会话记忆

## 手机设置与多执行节点（0.9.1）

每台白猫服务器管理自己的执行节点。服务器本机 Web 侧栏点「设置」登记节点，按返回的配置路径启动 worker。手机在「设置 → 服务器管理」添加或切换已配对的移动网关，日常在底部 Agent 页查看执行节点及各自的聊天。[注册与运行](agents.md)

手机底部「我的」中的「配置教程」提供本机 USB 或指定远程 HTTPS 的配置步骤。教程中的工作台 API 默认监听 18742，移动网关默认监听 18744。手机使用移动网关地址，执行节点上的 worker 连接工作台 API。远程主机可通过受信任的 TLS 证书直接提供网关，或在 HTTPS 代理后转发网关的 SSE 长连接。

此版本的 API 返回工作站实例标识、会话目录是否完整及已删除会话记录；worker 还上报已绑定原生历史的可用状态。升级时保留原数据库、配对凭据及 spool，在队列空闲时重启工作台 API 和 worker。手机使用缓存保留已删除会话的可读副本。

## 手机角色与迁移服务

0.7.0 需使用配套版本的 API、移动网关和 worker。API 自动为现有数据库添加角色与会话绑定，旧会话继续使用默认 Codex。worker 维护每段对话的原生线程；升级时先让正在执行的任务完成，并保留原数据库、工作站配置和 spool。旧版本 worker 只会领取兼容任务。

两台手机需要各自配对同一移动网关。网关用本地 SQLite 暂存有期限的加密资料，领取绑定到接收设备，确认导入后清除对应密文。对外接入沿用 HTTPS 或已授权的本地转发。二维码包含解密材料，日志与工单只记录传输状态。

## 14d 工作站

工作站使用本机已登录的 Codex CLI，以及用户数据目录中的 `station.json`、`worker.key` 和 `station/`。配置文件只保存路径及运行参数。Windows 当前数据目录为 `%LOCALAPPDATA%\WhitecatDesk`。本次已在原服务上加载配置并完成进程重启验收。[配置示例](station.example.json)

```powershell
# 在 whitecat 目录；首次建立配置，已有 station.json 时直接编辑原文件。
$station = Join-Path $env:LOCALAPPDATA 'WhitecatDesk'
if (!(Test-Path (Join-Path $station 'station.json'))) {
    Copy-Item deploy/station.example.json (Join-Path $station 'station.json')
}
python -B worker.py --config (Join-Path $station 'station.json')
```

| 字段 | 含义 |
| --- | --- |
| `server` | 白猫 API 的本机地址。分机部署时使用已授权 SSH 隧道的本地端口。 |
| `key_file` | 白猫服务机生成的工作站凭据路径。 |
| `spool` | 保存领取回执、待上报结果及每个会话线程绑定的目录。更换服务机时使用独立目录。 |
| `name` | daemon 在心跳中上报的名称，手机的工作站设置页会显示。 |
| `timeout` | 单轮 CLI 运行时限，单位为秒，可设 15–3600。 |

相对路径以配置文件所在目录为基准。命令行参数优先于配置文件。修改后等当前任务结束，再重启 daemon。每个 spool 同时只允许一个工作站进程使用。

## Linux 用户服务配置

将白猫代码放入 `~/whitecat`，配置文件和私有工作站凭据放入 `~/.config/whitecat/`。将示例配置的 spool 指向持久目录。用部署账号登录 Codex，并保证用户服务的 PATH 能找到 codex。API 与工作站分机时，先按 [运行说明](../README.md) 建立 SSH 转发。

```sh
install -Dm644 ~/whitecat/deploy/whitecat-worker.service ~/.config/systemd/user/whitecat-worker.service
systemctl --user daemon-reload
systemctl --user enable --now whitecat-worker
systemctl --user status whitecat-worker
```

本次实际运行的 daemon 在 14d Windows 工作站。以上 unit 供 Linux 部署使用。

## 手机配置与会话操作

- 会话列表支持搜索、按日期分组和查看最新一轮摘要。加号创建会话；当前会话会高亮。
- 工作台底部「我的」打开设置。已有聊天也可点顶部工作站状态查看当前会话的 CLI 续聊命令。可以重新配置服务地址和一次性配对码、选择会话列表的 2/5/15 秒刷新间隔、设置回车发送。手机连接地址使用移动网关，模拟器为 `http://127.0.0.1:18744`；daemon 连接的是工作台 API。
- 每个服务地址使用自己的缓存和草稿。未确认发送时，App 保留原正文与命令编号，等原工作站返回回执。已配对工作站的凭据继续由 Android Keystore 保护。
- App 断网可重开已缓存的会话、搜索缓存列表、编辑草稿。聊天页把已显示的消息与对应游标一起保存。重连时从该游标接收更新，首次打开会话时读取快照。远端新答复不会覆盖尚未发送的本机草稿。

## 从工作站命令行继续同一条会话

下面的入口与手机共用白猫 API 和同一聊天编号。CLI 的 list、history、send 均输出 JSON。发送的正文从 UTF-8 标准输入读取。如连接中断，保留原命令编号与正文重试。

```powershell
python -B chat_cli.py list
python -B chat_cli.py history CHAT_UUID
$commandId = [guid]::NewGuid().ToString()
$OutputEncoding = [Text.UTF8Encoding]::new($false)
'继续上一轮的计算。' | python -B chat_cli.py send --chat CHAT_UUID --command $commandId
```

## 三处历史如何对应

白猫数据库保存用户可见的消息、任务、活动和结果。工作站以服务实例与白猫会话编号定位独立工作目录，在 Codex 自己的 sessions 目录持久化原生线程。每次成功上报后，工作站保存已确认的白猫任务、Codex 线程编号与历史文件摘要。下一轮同时匹配这些记录才会执行 `codex exec resume THREAD_UUID`。手机保存的是同一会话的已确认消息快照和它自己的草稿。

从旧版升级时，工作站会先核对原生历史的已确认前缀，再导入后续轮次。无法找到可恢复的旧线程时，白猫能用服务端已完成的历史重建，包含文本附件和回复；重建受模型上下文容量限制。已有同步检查点的原生文件如果被截断或改写，daemon 会暂停同步该会话，保留双方的历史供核对。

[手机与 daemon 重启后的四轮一致性证据](../android/memory-acceptance.json) · [旧会话九轮历史重建证据](../history-recovery.json) · [手机断网重试证据](../android/android-acceptance.json)


## 在手机和原生 Codex CLI 之间交接

1. 在运行 daemon 的同一系统账号、同一个 `CODEX_HOME` 下直接运行 `codex`。一轮回复结束后，打开白猫会话列表，按首条提问搜索。原生终端发起的轮次在手机中标为「工作站 · Codex CLI」。
2. 保持原生 CLI 窗口打开，在白猫发送下一问。14d 上的 daemon 使用 Codex 的共享队列。终端和手机可以同时提交，当前轮完成后，Codex 在同一线程执行已排队的一轮。
3. 回到终端时，在手机会话的工作站设置里复制 `codex resume <线程编号>`，在该工作目录执行。已经打开的终端会自动显示手机消息和回复。手机断网时也可以点发送；请求保存在本机，恢复连接并打开聊天页后自动按原编号补发。

daemon 每四秒重新发现可用的交互线程。默认读取该工作站账号的交互式 CLI 历史；要限定导入目录，在 station.json 配置 `"native_roots": ["D:/work/my-project"]`，待任务完成后重启 daemon。已绑定会话继续跟踪。配置项使用 daemon 账号的文件系统路径，只能同步本机仍保留的会话文件。

白猫用原生线程和轮次编号去重。持久化完成的提问与最终答复进入手机历史，中断的轮次标明状态，图片以占位文字表示。工具输出、系统提示与内部推理留在工作站。daemon 将手机派发的原生轮次关联到原任务；同步器下次读到同一轮时会复用这条记录。

历史读取支持活动目录中的 .jsonl 和 .jsonl.zst。Python 3.14 可使用标准库 compression.zstd；较早的 Python 需要在 daemon 环境安装 zstandard。轮询只读压缩文件。第一次从手机续聊该归档时，daemon 如需还原可写的原生会话文件，会以原子操作创建同名 .jsonl 并保留原压缩文件。未结束的轮次会等待原生结束事件；同一文件后续已经结束的消息可以先同步。


## 共享终端连接

14d 已验证原生 Codex CLI 0.171.0、官方 app-server daemon 0.172.0 和白猫 Android 0.5.0。daemon 与 TUI 需使用同一账号及 `CODEX_HOME`。用 `codex app-server daemon version` 检查状态；新部署可先执行 `codex app-server daemon start`，再正常打开 `codex` 或 `codex resume <线程编号>`。白猫工作站的 Python 环境需要 `wsproto`（可用 `python -m pip install wsproto` 安装）。共享控制套接字仅在工作站本机使用；手机通过已配对的白猫移动网关发送。

手机的命令编号在后端幂等受理，工作站以任务编号作为官方队列的 `clientUserMessageId`。白猫利用原生用户项的 `client_id` 和线程、轮次编号关联结果。终端开始执行后，白猫 Android 和 Web 的当前聊天通过 SSE 接收用户可见的说明、正在生成的回复和工具状态。手机设置里的刷新间隔只影响会话列表。终端进行中追加的指令会保留在同一轮回复前。服务失去原生入队回执时，工作站核对队列和已保存的消息编号；结果仍未知时保留待核实状态，后续原生记录可以补齐。

使用 `--no-daemon` 的独立终端由独立进程持有写入锁，实时双端队列仅适用于连接到同一官方共享 daemon 的 CLI。已关闭的会话仍支持工作站按 16d 的方式恢复和派发。

[共享终端与模拟器验收](../android/shared-cli-acceptance.json)

## 流式聊天和断线恢复

工作站从本机共享 app-server 订阅原生通知。白猫按原生线程、轮次和项目编号保存每一段可见文本或工具状态。工具行显示名称、执行中、完成或失败，计时取自工作站。工具上报进度事件时才更新进度。原始工具参数、终端输出和内部推理留在工作站。

Android 与 Web 订阅 `GET /api/chats/<会话编号>/stream`。移动端沿用配对认证。服务端提交 SQLite 事务后推送，空闲时约每两秒发送心跳。手机重启后携带 `Last-Event-ID`，用服务端返回的内容更新同一条消息。反向代理须允许 SSE 长连接并及时转发每一帧。独立 `--no-daemon` 终端或回落到 `codex exec` 的工作站只能同步已持久化的历史，实时原生增量需要共享 daemon。

[Android 断线续传与原生执行核对](../android/stream-acceptance.json)

# 白猫远程工作台

## 服务器、Agent 和聊天（Android 0.9.1）

白猫手机可记住多台服务器。点底部「我的」打开设置，再选「服务器管理」，添加移动网关、完成配对或切换工作站。首页显示当前服务器、选中的执行 Agent 及其最近会话。「Agent」页列出该服务器已接入的执行节点，点一个节点查看或新建聊天。每条聊天的续问都交给原执行节点。

服务器操作者在本机 Web 侧栏打开「设置」，登记执行 Agent 的 ID 和名称，随后按生成的配置启动 worker。已有会话保留在默认 Agent。外观、本机 USB 与指定远程主机教程，以及应用版本和本机存储信息，都在手机「设置」内。[Android 安装包](android/白猫.apk) · [手机说明](android/README.md) · [服务器端接入 Agent](deploy/agents.md)

## Android 工作台 0.7.0

[安装白猫](android/白猫.apk) 后可在首页查看工作站和最近会话，在 Agent 页管理 Codex 角色，在迁移页把选中的历史、草稿及偏好加密迁至另一台已配对手机。会话列表提供仅本机删除。角色绑定与原生 Codex 线程保存在工作站，支持其他设备接续。[手机操作与配对](android/README.md)

一个人使用的远程 Codex 工作台。浏览器向白猫 API 提交消息，工作站通过 HTTP 主动领取任务，在每个会话独立的持久工作目录调用本机已登录的 Codex CLI，再把执行活动与最终回复回传。API 把会话、命令回执和结果保存在 SQLite。页面支持历史会话、追问、UTF-8 文本附件、活动详情、下载回复和断线重试。

## 本机运行

需要 Python 3.10 或更高版本。工作站需要已安装并登录的 Codex CLI，可用 `codex login status` 检查。运行时只使用 Python 标准库。Windows 已验证 Codex CLI 0.171.0 与 Edge。分别在本目录的两个终端启动以下程序。

```powershell
python -B server.py
```

```powershell
python -B worker.py
```

打开 [白猫工作台](http://127.0.0.1:18742/)。看到「工作站在线」后，输入任务或附上 32 KB 以内的 UTF-8 文本。每次发送生成一个命令编号，浏览器在收到回执前将编号和原内容保存在本地。页面提示发送未确认时，点「按原请求重试」。每轮的「下载回复」导出工作站返回的最终文本。

Windows 的数据位于当前用户 `%LOCALAPPDATA%\WhitecatDesk`，Linux 默认位于 `~/WhitecatDesk`，均可用 `WHITECAT_STATE` 指定目录。API 首次启动时生成 `worker.key`。工作站的 `station/active.json` 保存当前领取回执与待上报结果。保留这些文件可在重启后恢复。运行中的前台进程可用 Ctrl+C 停止。

## API 与 Codex 在两台机器上运行

1. 在服务机运行 `python -B server.py`。API 默认仅监听 `127.0.0.1:18742`。这是单用户版，本机用户和持有该机 SSH 转发权限的人均被视为工作台操作者。不要将此端口直接反向代理到公共网络。
2. 在工作站准备 `worker.py` 和自己的 Codex 登录，使用已授权的 SSH 账号通过 SFTP 或 SCP 将服务机生成的 `worker.key` 放入工作站的个人私有目录。该文件只用于工作站 API。
3. 在工作站建立到服务机的隧道。将下列命令中的 `USER@SERVER` 替换为服务机的 SSH 登录地址，保持该终端运行。首次连接先核对服务机 SSH 主机指纹。
4. 在工作站的另一终端运行 worker。工作站只需出站连接。操作者可在服务机打开工作台，也可从有独立 SSH 转发的另一台设备打开同一地址。

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:18742:127.0.0.1:18742 USER@SERVER
```

```sh
python -B worker.py --server http://127.0.0.1:18742 --key-file /path/to/private/worker.key
```

首次试运行建议上传一份只含几行数字的文件，请 Codex 统计条数与总和，再在原会话追问。连接不同的服务机时，为每个对应关系指定独立的 `--spool` 目录。

## 派发与恢复

- API 在一次 SQLite 事务里写入任务、聊天编号、唯一命令编号和入队活动，提交后才发送回执。同键同内容返回原编号，同键不同内容返回 HTTP 409。
- 工作站在领取前持久化领取编号。服务端在事务内选取队首任务并绑定该编号；同一领取请求丢回执时能找回同一任务。工作站在启动 Codex 前保存执行标记，执行结束后先保存最终结果，再向 API 回报。回报重试不会启动另一个进程。
- 工作站在执行中途崩溃且尚未持久化最终结果时，该轮在重启后标记「运行结果待核实」。API 或工作站掉线时，客户端可以继续查看已入库的消息。Web 当前聊天通过 SSE 接收回复与工具状态，会话列表在可见时定期刷新。后端以最近 20 秒内的工作站心跳判断在线状态。
- 工作站使用只读沙箱，按白猫会话恢复对应的 Codex 原生线程。首次迁移或线程记录失配时，从服务端的完整已完成历史重建。手机、桌面和命令行共用会话编号。[daemon、App 配置与历史恢复](deploy/README.md)

## 验收与实现入口

真实浏览器验收需要 Playwright 和 Edge。以下命令各建立一条新的测试会话并实际调用 Codex 两次。

```powershell
uv run --with playwright python -B verify_live.py --evidence acceptance.json
uv run --with playwright python -B verify_wsl.py
```

第二条命令适用于 Windows 上启用了 localhost 转发的 WSL2 Ubuntu-22.04，会在 18743 端口建立临时 Linux API，与独立 Windows 工作站通过 TCP 通信。结束后关闭测试进程并清理测试数据库。平时使用的 18742 工作台保持运行。

- [服务端](server.py)、[工作站](worker.py)、[网页](web/index.html)。
- [同机分进程验收](acceptance.json)、[跨系统验收](acceptance-wsl.json)、[项目操作记录](../操作记录.md)。
- 后端参考本地 Multica 的 `server/internal/service/task.go` 中的领取与结果回写边界。前端参考 Orca 的移动会话活动行，白猫将运行活动折叠在回复内，保留状态、时间与可展开的明细。[Orca 移动组件](https://github.com/stablyai/orca/blob/d55ba30b/mobile/src/session/native-chat/transcript/messages/MobileNativeChatEventRow.tsx)

白猫提供响应式 Web 工作台、单用户工作站和[原生 Android 安装包](android/白猫.apk)。Android 通过单独配对的移动入口连接同一后端，安装、转发、配对与模拟器验收见 [Android 使用说明](android/README.md)。公网多用户身份和任意二进制附件仍为后续范围。

在工作站直接运行 `codex` 的交互式会话也会出现在手机列表。手机设置可以复制同一原生线程的恢复命令；切换步骤与可选工作目录范围见[原生 CLI 与手机交接](deploy/README.md#在手机和原生-codex-cli-之间交接)。

保持原生 CLI 打开即可与白猫手机共同使用该线程。14d 工作站通过 Codex 官方本机共享队列派发，Android 断网待发消息在重连后自动补发。聊天页随原生 CLI 显示可见回复和工具进度。模拟器中途断线并重启 App 后验证了续传和请求去重。[流式验收](android/stream-acceptance.json) · [配置说明](deploy/README.md#共享终端连接) · [并发与断网验收](android/shared-cli-acceptance.json)

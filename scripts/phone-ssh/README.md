# ETA 手机连接电脑上的 Codex

适配当前手机 Debian 13 / ARM64。手机原有 VPN 继续使用；Android Tailscale 停止，改由 ETA Linux 内的用户态 `tailscaled` 建立连接。

## 当前电脑

- OpenSSH / Tailscale：Windows 服务，自动启动。
- SSH：`bian@100.122.92.56:22`。
- SSH 用户名是 Windows 账号 `bian`。Tailscale 设备名 `bianwuya` 不能作为登录用户名。
- 主机 Ed25519 指纹：`SHA256:wEnvw4G6bXo32HCfmSl/jQ8UHQqnW+Evh1qKL+zWRtc`。
- 独立 CLI：`F:/Apps/CodexPhoneSSH/cli`，官方 `@openai/codex@0.165.0-alpha.1.2`。
- 原生会话守护：`codex app-server daemon`，复用当前用户的 Codex 配置与认证。
- 手机入口先查询并复用已有守护，不会经 SSH 请求启动 Windows 后台守护。守护由 Windows 登录时的启动快捷方式以普通用户令牌启动。
- Windows 登录自启：`Codex SSH Session Daemon.lnk`，隐藏启动 `phone-codex.ps1 -DaemonOnly`。
- 公钥授权：管理员 PowerShell 7 运行 `authorize-phone.ps1 -PublicKeyPath C:/Users/bian/.ssh/eta-phone.pub -UserName bian`。

`bian` 属于 Windows Administrators，因此本机现有 `sshd_config` 要求公钥位于 `C:/ProgramData/ssh/administrators_authorized_keys`。脚本保留原有公钥，只添加新公钥并设置 OpenSSH 要求的文件 ACL。

## 手机首次配置

1. 保留 `~/.ssh/id_ed25519_eta_pc` 私钥；无需传到电脑。
2. 将官方 ARM64 Tailscale 静态程序放到 `~/.local/share/eta-tailscale/bin/`。
3. 用 ETA 的 `terminal_daemon` 在 Linux 环境启动 `eta-tailscale.sh`，保留返回的 `task_id`。
4. 用同一目录的 `tailscale --socket=.../tailscaled.sock up --hostname=eta-phone-linux --accept-dns=false` 完成一次登录。登录链接由本人打开，不传递登录凭据。
5. 把 `eta-pc.conf` 安装为 `~/.ssh/eta-pc.conf`，在 `~/.ssh/config` 顶部添加 `Include ~/.ssh/eta-pc.conf`。保留其他配置。
6. 把下面这行追加到 `~/.ssh/known_hosts`，并核对上述指纹：

```
eta-pc ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILx6y0OuDcr9OXeZAOcr2XQLt9LVy76GbyigxnIBXZzJ
```

7. 验证 `ssh eta-pc whoami`，再运行 `eta-codex` 进入原生会话列表。

连接验证阶段：用 ETA `terminal_daemon` 在 Linux 启动 `ssh -MN eta-pc`，再执行 `ssh eta-pc whoami` 和远端 `phone-codex.ps1 -Check`。使用 `ssh -S none -o ControlMaster=no` 连续执行两次 `-Check`，可验证独立 SSH 连接的建立和结束；这不等于进行中模型任务的断线恢复。基础连接通过后，再配置网络中断后的自动重连，避免只持久化地址却仍需手动重建连接。

自动重连入口为 `eta-ssh-master`，需要手机 Debian 的 `autossh`。将脚本装到 `~/.local/bin/` 后，停止验证用的 SSH master 守护，再用 ETA `terminal_daemon` 在 Linux 启动 `/root/.local/bin/eta-ssh-master`。它通过 OpenSSH 的 ServerAlive 检测失联，由 autossh 以退避方式重建连接，包含开机后网络尚未就绪的首次失败，错误保留在 ETA 守护日志。转发端口仅绑定电脑 `127.0.0.1:2222`，目标为手机 `127.0.0.1:8022`。Tailscale 与独立 sshd 守护仍单独保留。脚本不加 `-f`，并使用 `-S none`、`ControlMaster=no`、`ControlPersist=no` 保持被监督的 SSH 在前台；普通命令连接也关闭复用，避免异常退出遗留的套接字影响新连接。最终配置用实际 `ssh eta-pc whoami` 和 `ssh eta-phone id` 验证；`ssh -O check` 不再是此方案的健康检查。

## 已验证与待验证

已验证：独立官方 CLI 的版本、原生 daemon 启动与状态读取、原生 `codex agents` 会话列表打开、关闭 TUI 后 daemon 仍运行、Windows 登录启动项内容、PowerShell / POSIX Shell 语法，以及官方 Tailscale ARM64 归档的 SHA256。Windows 管理员公钥授权由本人运行并回传 `authorized`。手机 ETA Debian 用户态 Tailscale 已登录，节点 `eta-phone-linux` 为 `100.117.254.98`，ETA 守护任务 `dm_4938191a`；电脑确认节点在线并收到两次 DERP 响应。`tailscale ping` 因未建立直连返回 1，不能把该退出码当成完全不通；实测走中继。

手机已实测 `whoami` 返回 `bianwuya\bian`、退出码 0，公钥登录与严格主机校验正常。原 VPN 开启时 SSH 可用；其他手机应用尚未逐一测试。SSH 验证 master 为 ETA 守护 `dm_ada64ec2`。

已定位并处理的 Windows 问题：SSH 会话能读取 `.codex` 和 `current` 两层 Junction 的 Target，但无法通过链接访问子文件；同一会话可读取真实发布目录并运行独立安装包。启动器已逐层解析 Target，并将进程内的 CODEX_HOME 指向同一真实数据目录。SSH 调用 `daemon start` 还触发了 Codex 的非提权启动检查；手机入口现仅查询并复用已运行守护，启动由 Windows 登录快捷方式负责，未更改该安全检查。

手机 SSH 回归已通过：`ssh-launcher-test.json` 于 2026-11-11 16:21:12（Asia/Shanghai）记录两次 ExitCode=0、Status=running、Passed=true、标准错误为空；电脑直接读取核验。待验证：手机 autossh 自动重连、交互式 Codex 界面以及进行中任务接续。桌面应用与独立 CLI 是不同的客户端，不能仅凭列表中出现已保存聊天就认定能实时控制桌面正在运行的任务。

## 控制方向

2026-11-11 已在真实设备验证双向终端：电脑 `ssh eta-phone id` 返回 `uid=0(root)`、退出码 0；手机回连电脑的 `test-phone-codex.ps1` 两次检查均为 `Status=running`、退出码 0。手机主机公钥已固定在电脑 `known_hosts_eta_phone`，指纹为 `SHA256:BQQ5FdTDNVGbdlRgY18WE7gD+6oFGUXDX7tANy3DrHE`。电脑使用 `eta-phone` 连接本机 `127.0.0.1:2222`。双向终端控制不提供双方聊天上下文同步、消息发送或 Agent 会话控制。

## 持久化范围

SSH 密钥、主机名、Tailscale 节点状态均保存，后续无需重新输入地址或账号密码。常驻隧道由 autossh 恢复；交互式 `eta-codex` 的终端连接中断后仍需重新打开。电脑的原生 Codex 守护独立于 SSH 连接，重新连接后从会话列表继续。实际杀掉隧道 SSH 子进程后，原 autossh 在约 7 秒内建立新连接，双向命令再次通过；这不等于已经实测整机重启或所有网络故障。

旧版 ETA 的 `terminal_daemon` 不会在设备重启后恢复。2026-11-11 源码已新增默认关闭的 `restart_on_boot`：对本次三个服务显式启用后，开机广播（首次解锁后）、应用启动/前台回归与前台执行服务重建会恢复已退出的服务。普通命令、聊天任务不重放；`stop` 删除恢复记录，任务页可显示并停止“重启后恢复”的已退出服务。系统强制停止、Root 授权或 Linux 环境不可用仍可能阻止恢复。当前 RMX5200 手机已实际重启验证，首次解锁后三个服务恢复且双向命令通过；首次解锁前凭据加密存储不可访问，服务不会恢复。

新版启用示例：`terminal_daemon {"action":"configure","task_id":"<实际服务ID>","restart_on_boot":true}`。新服务可在 `start` 时直接指定同一选项。只启用 Tailscale、独立 sshd 和 autossh 各一个有效服务，不标记旧的认证失败或已被替换记录。

电脑关闭或睡眠时无法连接。ChatGPT 手机应用的官方远控仍要求电脑登录相同的 ChatGPT 账号；本方案使用 ETA 的 SSH 终端。

## 2026-11-11 构建交付

最新本地构建基于核心仓当前修改树，HEAD `a5b845fb244af37785d862fe1164f13b9b9d3460`，包含原有未提交修改。最终 `ssh-imagegen-delivery-build-20261111.log` 记录 31 项定向测试通过、Lint 无错误及原签名 Debug/Release 双包成功；交付目录 `F:/eta/07-构建与运行日志/交付/20261111-182253-SSH恢复-生图修复/`，签名与哈希见 `verification.json`。

同包修复生图配置页的 `credential_key_unavailable` 未捕获异常，并明确中转/OpenAI兼容API选择。页面在两种主题下测试通过，中转请求已用拦截器验证地址、Key、模型和单次调用；未发送真实付费生图。本机中转 `/v1/models` 无法连接，未生成或修改中转凭据配置。

手机已由本人覆盖安装，并于 2026-11-11 接入 ADB root。三项有效服务 `dm_c7e86add`（Tailscale）、`dm_36692211`（sshd）、`dm_3123a09e`（autossh）的 `restart_on_boot=true` 已从实际记录核对。三个服务全部退出后，通过现有开机接收器重新恢复，双向 SSH 通过；之后实际整机重启，首次解锁后再次确认三项服务和双向 SSH 恢复，手机调用电脑 Codex 的两次检查也全部通过。精确回执为交付目录的 `reverse-ssh-adb-verification.json`、`phone-after-reboot.json`。付费生图尚未实测。未自动安装、提交或推送。额外的旧 Linux 进程测试在 Windows 有 5 项失败，具体输出见 `ssh-imagegen-targeted-20261111.log`；未修改那些测试或把定向通过等同于全量单测通过。

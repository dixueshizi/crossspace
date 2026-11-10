# crossspace 使用说明（一页纸）

跨空间拉起应用：把「应用 + 目标空间 + 页面」交给设备端的 `am start --user N`。
支持微信、支付宝，也可扩展到 QQ / 抖音等；空间 ID 不写死，0 / 999 / 10–30 全域可用。

## 1. 部署（只需一次）

```powershell
cd crossspace
.\crossspace.ps1 -Deploy
```

成功会看到：`已部署：/data/local/tmp/crossspace/crossspace.sh` + 设备 env（uid/sdk/版本/用户列表）。
有 adb 就够；设备有 root 时会自动在需要时用 `su` 兜底。

## 2. 最常用的几条

```powershell
# 微信主界面 → 系统分身 999
.\crossspace.ps1 -User 999 -App wx -Page home

# 支付宝主界面 → 10 号空间
.\crossspace.ps1 -User 10 -App alipay -Page home

# 微信扫一扫 → 999
.\crossspace.ps1 -User 999 -App wx -Page scan

# 微信朋友圈 → 15 号空间
.\crossspace.ps1 -User 15 -App wx -Page sns

# 看有哪些空间、各空间装没装微信/支付宝
.\crossspace.ps1 -Users

# 批量：10–30 之间每个空间都拉一次微信
.\crossspace.ps1 -All -App wx -Page home -Range 10-30
```

## 3. 指定组件 / scheme

```powershell
# 明确指定组件（resolve 不到时用）
.\crossspace.ps1 -User 10 -Pkg com.tencent.mm -Component com.tencent.mm/.ui.LauncherUI

# 支付宝扫一扫：没有独立 Activity，走 scheme
.\crossspace.ps1 -User 10 -App alipay -Page "uri:alipays://platformapi/startapp?appId=10000007"
```

## 4. 切前台与回主空间（-Foreground / -Back）

ColorOS 的多开空间（999、10–16）全部是 `profile.CLONE`（`parentId=0`），
**系统不允许 profile 用户成为前台 user**，所以 `am switch-user <N>` 一定会失败
（实测 `Error: Failed to switch to user 10`，root 也无法绕过这个 user type 限制）。

但 `am start --user N` 启动的 Activity 会直接显示在当前屏幕上，`am get-current-user`
仍然是 0。所以这里的“切前台”不是切 user，而是确认顶层 Activity 已经是 `uN`：

```powershell
.\crossspace.ps1 -User 10  -App wx -Page home -Foreground   # 10–16 直接生效
.\crossspace.ps1 -User 999 -App wx -Page home -Foreground   # 999 首次可能弹选择器
```

- 999 是 ColorOS 的 MultiApp 空间，冷启动会先弹出系统选择器
  （`com.android.intentresolver/.ChooserActivityLauncher`，选项为「微信 / <用户名>.999 / 记住我的选择」）。
  首次在弹窗里勾选「记住我的选择」并点一下 999，之后 `-Foreground` 就会自动直达；
  若弹窗出现且未勾选，脚本会自动尝试点选 `.<user>` 后缀的条目，失败则报 `ERROR[77]`。
- 10–16 是普通克隆 profile，实测冷启动（force-stop 后）直接显示，不弹选择器。

**回主空间**（不会丢状态，主空间应用栈保持原样）：

```powershell
.\crossspace.ps1 -Back
# 等价于 adb shell input keyevent 3（KEYCODE_HOME）
```

副作用说明：这套做法不会切换 current user，主空间的进程/任务都保留，只是屏幕被
分身的 Activity 覆盖；按 HOME 即可回来。真正的 `am switch-user` 才会让主空间整体退到后台，
但它在这些空间上根本不可用，所以不采用。锁屏状态下需要先解锁。

## 5. 设备端直接调用（Termux / 自动化 App 内的 shell）

```sh
# adb shell 身份（通常已够，不需要 root）
sh /data/local/tmp/crossspace/crossspace.sh 999 wx home

# 设备内 root
su -c 'sh /data/local/tmp/crossspace/crossspace.sh 10 alipay home'
```

你的自动化工具如果是脚本驱动的，直接拼这一行即可，不用经过 PowerShell：

```sh
su -c 'sh /data/local/tmp/crossspace/crossspace.sh <空间ID> <wx|alipay|包名> <home|scan|sns|uri:...>'
```

## 6. 扩展应用名单（apps.conf，可选）

格式：`别名=包名`，或 `别名:页面=组件`（页面映射只在自动解析失败时用）。

```conf
qq=com.tencent.mobileqq
douyin=com.ss.android.ugc.aweme
alipay:scan=uri:alipays://platformapi/startapp?appId=10000007
```

改完重新 `-Deploy` 一次即可。

## 7. 退出码

| 码 | 含义 | 处理 |
|---|---|---|
| 0 | 成功 | — |
| 64 | 参数错误 | 看用法 |
| 65 | 用户 ID 不存在 | `-Users` 看现有空间 |
| 66 | 该空间未安装此应用 | 先在该空间装应用 |
| 67 | 解析不到组件 | 用 `-Component` 或 apps.conf |
| 68 | 权限不足（非 root 也非 shell） | 换 root 或 adb shell |
| 69 | am 被系统拒绝 | 换 `-Su` 重试 |
| 70 | 该页面不支持直达 | 用 scheme / 手动点 |
| 71 | 配置文件格式错误 | 检查 apps.conf |
| 72/73/74 | PC 侧：无 adb / 无设备 / 推送失败 | 检查 USB 调试 |
| 75/76 | 设备端执行失败 | 检查 root/权限 |
| 77 | 已启动但没切到目标空间前台 | 解锁；999 首次需在弹窗里选一次并勾“记住我的选择” |

## 8. 实测结论（ColorOS 15 / OnePlus PJX110，2026-11-08 ~ 11-09）

- 微信 → 999：成功，进程 `u999_a360`。
- 支付宝 → 10：成功，进程 `u10_a424`。
- 微信扫一扫 → 999：`am start` 返回成功（`BaseScanUI`）。
- 微信朋友圈 → 999：组件解析成功（SnsTimeLineUI）。
- 支付宝扫一扫/付款码：**没有独立 Activity**，组件方式不可用；scheme 方式能起 `SchemeLauncherActivity`，具体落在哪个页面待确认。
- 本机无 `su`（`adb shell which su` 为空），全部测试走 shell 身份，说明**不需要 root**。

### 切前台实测（2026-11-09）

- `pm list users`：`0`、`10`–`16`、`999`；`dumpsys user` 显示 10 和 999 都是
  `android.os.usertype.profile.CLONE`，`parentId=0`，Flags 含 `PROFILE`。
- `am switch-user 10` → `Error: Failed to switch to user 10`；`am switch-user 0` 无报错。
  shell 已持有 `INTERACT_ACROSS_USERS_FULL`，所以**不是权限问题，是 profile 不允许做前台 user**。
- `am start-user -w 10` → `Success: user started`，但 `am get-current-user` 仍为 0（只后台启动，不切前台）。
- `am start --user 10 -n com.tencent.mm/.ui.LauncherUI`（force-stop 后冷启动）→
  `topResumedActivity=... u10 com.tencent.mm/.ui.LauncherUI`，屏幕直接显示 10 号空间微信。
- `am start --user 999 ...` 冷启动 → 弹出 ColorOS 多开选择器
  （`u0 com.android.intentresolver/.ChooserActivityLauncher`，含「微信 / <用户名>.999 / 记住我的选择」）；
  勾选“记住我的选择”并点 999 后，后续直接进 `u999`，不再弹窗。
- `-Foreground` 实测：999 和 10 都输出「已在目标空间前台」，`topResumedActivity` 为对应 `uN`。
- `-Back`（`input keyevent 3`）→ `topResumedActivity=... u0 com.android.launcher/.Launcher`。
- 全程 `am get-current-user` 保持 0，说明**没有切换用户，主空间状态未受影响**。

## 9. 相关：跨空间支付选择器（阶段 2）

阶段 2（别的应用发起支付时，在选择框里列出各空间的同一应用）不是脚本能力，需要 LSPosed 模块，
原型在 [proto/README.md](proto/README.md)，设计与结论见 [../docs/跨空间调用方案.md](../docs/跨空间调用方案.md) 13.8。

2026-11-09 已改成**自建选择器**并实测通过：`alipays://` / `weixin://` 触发后弹出「微信/支付宝 · 主空间 /
999 / 10–16」列表，点选后分别以 u0、u12、u15、u999 启动（`topResumedActivity` 已核对）。
不再依赖系统选择器，因此 ColorOS 的「记住我的选择」缓存与别名统一化都不再影响它。

2026-11-10 修掉「图片分享给微信，点任何空间都进 999」：一是删掉「主空间走系统原生链路」的快捷分支
（ColorOS 会把第三方发起的微信启动改到 u999），二是停止用 `ShareProvider` 包装分享 URI
（Android 11+ 包可见性下微信读不到、会自己关窗），三是改成以本模块包身份 `grantUriPermission` 逐条授读权限。
回放延迟固定 900ms（2000ms 时后台启动会被系统丢弃）。修复后主空间 / 999 / 12 三条分享全部落到对应空间微信的
`SelectConversationUI`，支付宝多空间、桌面图标不弹选择器两条回归复测无异常。细节见 `proto/README.md` 4.1。

2026-11-10 v0.3：修掉「图片分享给主空间微信打不开」。根因是 Android 15 在 `ActivityStarter` 里新增的
"EXTRA_STREAM 的 content:// 必须由发起方自己持权"校验 —— 微信 `ShareImgUI.onSceneEnd` 二次
`startActivity(MsgRetransmitUI)` 时发起方是 u0 微信自己，拿不到媒体 URI 授权就 `SecurityException` 崩；
「收藏/朋友圈/微信状态」不走这条二次启动所以正常。解法：只对 u0+微信这一单把 `EXTRA_STREAM` 和 `ClipData`
换成 `file://真实路径`，微信按路径读不走 UriGrant 校验，实测 u0 正常进 `SelectConversationUI`，
u12 等其它空间路径未改动、回归正常。细节见 `proto/README.md` 4.2。

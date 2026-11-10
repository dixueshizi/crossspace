# 跨空间选择器（crossspace-proto）

LSPosed 模块。外部应用（或系统）要拉起微信 / 支付宝时，弹出自建选择器，列出
**主空间 0 + 999 + 10–30** 里装了同一应用的实例；选中哪个，就以那个空间的身份启动。

已实测通过：ColorOS 15 / OnePlus PJX110 / SDK 35 / 2026-11-09。

## 1. 现在的实现（不是最早那版）

最早的最小验证是「往系统选择器里追加一条 u15 候选」，链路能通（见 `../../docs/跨空间调用方案.md` 13.8.1），
但它有三个硬伤，所以换成了自建选择器：

| 系统选择器的问题 | 后果 |
|---|---|
| ColorOS 用 `getMultiAppAlias(pkg)` 统一生成别名（如「<用户名>.999」） | 我们写在 `ResolveInfo` 上的「微信 · 空间12」被覆盖，10–16 在框里分不出来 |
| `ResolverListController` 对「同一组件多 user」的比较器不满足传递性 | TimSort 抛异常，选择器直接不出来 |
| 999 由系统自己列一次 | 再加一次就重复，只能靠 `(user, component)` 去重 |

自建选择器把候选和 UI 都拿回自己手里：

```
外部 intent
   │
   ├─ PMS 注入（hook ComputerEngine#queryIntentActivitiesInternal）—— 仍在，作为系统侧兜底
   │
   └─ ATMS startActivityAsUser（hook）
         │  外部应用、目标是微信/支付宝、且在 ≥2 个空间装上
         ├─ 按 user 逐个 queryAsUser() → 生成候选（pkg/activity/user/label）
         └─ 用显式 component 启动 SpaceChooserActivity（自建 UI）
                │  用户点某个空间
                └─ 回 ATMS：清 Binder 身份 + system context.startActivityAsUser(intent, UserHandle.of(N))
```

关键代码位置：

| 环节 | 位置 |
|---|---|
| 候选枚举 | `Main.spaceChooserIntent()`（`ALL_USERS` = 0 + 999 + 10–30，按 `(pkg,name,user)` 去重） |
| 跨 user 查询 | `Main.queryAsUser()`（`Binder.clearCallingIdentity()` + 复用同一个 `ComputerEngine`） |
| 拦截外部启动 | `Main.hookAts()` 里的 `ActivityTaskManagerService#startActivityAsUser` |
| UI | `SpaceChooserActivity`（`Theme.Material.Light.Dialog`，`singleInstance` + `onNewIntent` 重建候选） |
| 回放 | `Main.hookAts()` 的 `EXTRA_FROM_CHOOSER` 分支 |

两条必须遵守的规则（都是踩出来的）：

1. **不要在 `startActivityAsUser` 的 int 形参上改 userId**。各 ROM 重载顺序不同，改错下标不报错、
   只是静默落在 u0（实测：点「空间15」后 `topResumedActivity=u0 ... AlipayLogin`）。
   用 `ContextImpl#startActivityAsUser(Intent, UserHandle)`，user 是显式的。
2. **排除目标应用自己发起的 intent**。微信在 999 起来后会继续发自己的深链跳转
   （`WXCustomSchemeEntryActivity` → `MMWebViewUI`），若不排除，会在这台分身上再叠一层选择器，
   表现为「选了空间却退不出去」。判据是调用方 uid 的 appId 与 `WATCH_PKGS` 相同（`isWatchedCaller`）。

## 2. 编译

```powershell
$env:ANDROID_JAR  = "<SDK>\platforms\android-35\android.jar"
$env:BUILD_TOOLS  = "<SDK>\build-tools\34.0.0"
$env:JAVA_HOME    = "<JDK 或 Android Studio 自带 jbr>"
powershell -File build.ps1
```

不设 `ANDROID_JAR` / `BUILD_TOOLS` 时，脚本会尝试从 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 下自动找
`build-tools` 与 `platforms/android-35/android.jar`；三个变量都找不到时会直接报错退出。

依赖：Xposed API 的 `lib/api-82.jar`（仓库不收录二进制，请自行从 Xposed / LSPosed 官方源获取后放到该路径）。

产物：`crossspace-proto.apk`（自动生成 `debug.keystore` 签名，仅本地调试用）。

**dex 步骤优先用 Android Studio 自带的 `r8.jar`**（可用 `R8_JAR` 环境变量覆盖）。
原因：build-tools 里的 R8 8.2.2 遇到「带外部实例的匿名内部类」会抛
`NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`，
只指向 `SpaceChooserActivity$1.class`（做过最小复现，代码本身没问题）。另外 `d8 --no-minification`
是非法选项，脚本里已去掉。

## 3. 安装与启用

```powershell
adb install --no-incremental -r crossspace-proto.apk
```

LSPosed 里勾选模块，作用域至少：`system`（system_server / android）、`com.android.intentresolver`。
装完重启一次（软重启即可）。**装完立刻重启偶发 `PackageParserException` 解析失败**，再重启一次就好。

没有 LSPosed 管理器 UI 时，也可以直接改 `/data/adb/lspd/config/modules_config.db` 完成注册，等价 SQL：

```sql
insert or replace into modules values('com.crossspace.proto', '<apk 实际路径>');
insert or replace into modules_state values('com.crossspace.proto', 0, 1, 0);
insert or replace into scope values('com.crossspace.proto', 'android', 0);
insert or replace into scope values('com.crossspace.proto', 'system', 0);
insert or replace into scope values('com.crossspace.proto', 'com.android.intentresolver', 0);
```

改完 `lspctl stop`，替换 db 并把 `-wal`/`-shm` 清零，再重启。

## 4. 实测记录（2026-11-09，ColorOS 15 / PJX110）

触发命令（`alipays://` 必须带 `BROWSABLE`，否则 shell 侧直接解析不到）：

```powershell
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE `
  -d "alipays://platformapi/startapp?appId=20000067"
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE `
  -d "weixin://dl/business/?t=abcdef"
```

| 用例 | 选择器候选 | 点选后 `topResumedActivity` | 结论 |
|---|---|---|---|
| 支付宝 `alipays://` | 支付宝 · 主空间 / 999 / 10–16（9 项） | `u15 com.eg.android.AlipayGphone/.AlipayLogin` | 通过 |
| 微信 `weixin://` → 999 | 微信 · 主空间 / 999 / 10–16（9 项） | `u999 com.tencent.mm/.plugin.webview.ui.tools.MMWebViewUI` | 通过 |
| 微信 → 空间12 | 同上 | `u12 com.tencent.mm/.plugin.base.stub.WXCustomSchemeEntryActivity` | 通过 |
| 微信 → 主空间 | 同上 | `u0 com.tencent.mm/.plugin.webview.ui.tools.MMWebViewUI` | 通过 |
| 选择器已弹出时再触发第二种支付（支付宝 → 微信） | 同一实例刷新为「微信 · …」9 项，图标同步切换 | 点「空间999」→ `u999 .../MMWebViewUI` | 通过（`onNewIntent` 生效，无旧数据残留） |
| 图片分享 ACTION_SEND → 微信，逐个空间点选 | 每个空间只给一条「微信 · 空间N」（原先把微信 4 个分享入口都列出来，54 条重复） | u0 → `u0 .../SelectConversationUI`；u10 → u10；u12 → u12；u15 → u15；u999 → u999 | 通过 |
| NFC「碰一碰」拉起支付宝 → 选择器 | 9 项 | 点选各空间后由对应空间支付宝响应 | 实机通过 |

**候选去重规则**：按 `(包名, user)` 去重，一个应用在一个空间只出一条。
分享时同一个包在同一空间能解析出多个入口（微信 `ShareImgUI` / `ShareToTimeLineUI` / `WXEntryActivity`…），
早期按 `(包名, Activity, user)` 去重会列出 5 条一模一样的「微信 · 主空间」，一共 54 条，
既难选也容易点错行——「选了 12 却像是开了 999」的反馈就是从这里来的。

排查时可用两条现成日志：选择器侧 `bind: n=N first=… last=…`、`pick pos=… label=… user=…`，
system_server 侧 `replay ok: user=N`。

反向验证（不该弹的时候不能弹）：从桌面点微信/支付宝图标 → **直接以 u0 打开，不弹选择器**，日志无拦截记录。
靠 `isExternalPkg()` 排除 launcher（本机是 `com.android.launcher`）、systemui、intentresolver、`com.oplus.*`、`com.coloros.*`。

关键日志：

```
[CrossSpaceProto] spaceChooserIntent: candidates=9 [支付宝 · 主空间, 支付宝 · 空间999, ..., 支付宝 · 空间16]
[CrossSpaceProto] start -> chooser spaces=9
[CrossSpaceProto] replay ok: user=15 I{android.intent.action.VIEW data=alipays://... cmp=com.eg.android.AlipayGphone/com.alipay.mobile.quinox.SchemeLauncherActivity}
```

候选去重是构造上成立的：列表只从 `ALL_USERS` 枚举，不读系统选择器已有条目，所以不会出现
「999 被列两次」。系统自带的 999 选择器（`com.oplus.multiapp`）在这条链路上完全不参与。

## 4.1 图片分享到微信的修复（2026-11-10，重要）

现象：相册分享图片给微信，选择器列出 9 条「微信 · 各空间」，**点哪条都落到 u999**。三条原因叠加：

1. **ColorOS 会把第三方发起的微信启动静默改到 u999**。原先「目标空间 == 调用方空间」走的快捷分支
   （清 extras 直接 return，交给系统原生链路）实测落在 `u999 ... ShareImgUI`，该分支已删除，全部空间统一由模块回放。
2. **`ShareProvider` 包装 URI 失效**。Android 11+ 包可见性下，微信读不到 `com.crossspace.proto.share`，
   日志 `E ActivityThread: Failed to find provider info for com.crossspace.proto.share`，微信 200ms 内关窗。
   `wrap1()` 现在是恒等函数（返回原 `content://media/...`），provider 保留但不再使用。
3. **system 身份不能授 URI 读权限**（`UriGrantsManagerService` 明确拒绝）。
   改成以**本模块包身份**授权：`ensureOwnMediaPerm(user)` 确保模块在目标空间持 `READ_MEDIA_IMAGES`，
   再 `grantViaOwnApp()` 用 `createPackageContextAsUser("com.crossspace.proto", ...)` 逐条
   `grantUriPermission("com.tencent.mm", uri, FLAG_GRANT_READ_URI_PERMISSION)`。

回放顺序：`p.setResult(0)` → `postDelayed(900ms)` → `clearCallingIdentity` → `systemContext()`
→ `ensureOwnMediaPerm(tu)` → `grantViaOwnApp()` → `ContextImpl#startActivityAsUser(intent, UserHandle)`。

**延迟只能 900ms 左右**：拉到 2000ms 时选择器已 finish、发起方退到后台，system 身份的后台启动会被 BAL
规则静默丢弃（日志照样打 `replay ok sys user=N`，但 `dumpsys` 里连微信 task 都没有）。判失败前给足 10s。

实测（看图页 → 分享 → 微信 → 发送给朋友 → 点行）：

| 点选 | 落盘日志 | 结果 |
|---|---|---|
| 微信 · 主空间 | `own perm ok u=0` / `owngrant com.tencent.mm u0 <uri>` / `replay ok sys user=0` | `u0 .../.ui.transmit.SelectConversationUI` |
| 微信 · 空间999 | `replay ok sys user=999` | `u999 .../SelectConversationUI` |
| 微信 · 空间12 | `replay ok sys user=12` | `u12 .../SelectConversationUI` |

支付宝 `alipays://` 回归：主空间 / 999 / 12 → `u0` / `u999` / `u12` `AlipayLogin`，无回归。

**装没装上新版只看 `BOOT rev=`**（现为 `2026-11-10T1400-owngrant`）：出现过 MD5 与本地 APK 一致、
dex 里也有新字符串，但落盘 `BOOT rev` 还是旧值的情况——LSPosed 加载的是旧 dex，要重装 + `kill -9 system_server`。

注意：`com.crossspace.proto` 未安装到 999 空间，999 那条 `grantViaOwnApp` 等于空转（本次实测通过是因为该空间微信
对媒体 URI 已可读）。若 999 出现读不到图，先把模块包装到 999。

## 5. 当前边界（如实）

- 只处理外部应用发起的 intent：`userId == 0` 发起方 + `WATCH_PKGS`（微信、支付宝）硬编码在 `Main.java`。
  其它应用（QQ/抖音）要加进 `WATCH_PKGS` 再编译。
- **每次必弹**：自建 UI 没有「记住我的选择」，系统缓存也不再介入（这是有意的，避免上次的选择误导下一次）。
  要「记住」需要自己写 SharedPreferences 持久化。
- 不接管系统选择器 UI；`com.android.intentresolver` 作用域里还有探针/排序兜底 hook（给 PMS 注入那条旧路线用）。
- NFC「碰一碰」未实机验证：NDEF/scheme 拉起会进这条选择器，HCE APDU 在 NFC 控制器层按 AID 路由，Intent 层拦不到
  （结论仍是静态推断，见 `../../docs/跨空间调用方案.md` 13.7）。
- 锁屏状态下选择器能弹出，但系统的 USB 用途弹窗可能盖在上面；测试前先点亮并解锁屏幕更稳。
- 图标依赖 manifest 里的 `<queries>` 声明（Android 11+ 包可见性），删掉会出现图标空白。

## 6. 回退

```powershell
adb uninstall com.crossspace.proto          # 或 LSPosed 管理器取消勾选
```

若出现 system_server 软重启循环：进 recovery 删模块目录 / 卸载 APK，本模块不写系统分区。

## 7. 风险提示

hook 在 system_server 内，异常已全部包 try/catch，但仍有拖慢解析、干扰多开选择器的可能。
`SpaceChooserActivity` 为了能被 system_server 拉起必须 `exported="true"`（系统 uid 不受该限制，
但 hook 走的是 system context 之外的一跳），其它应用理论上也能直接启动它；
它只读自己 intent 里的 extras，不做敏感操作，最坏情况是被拉起一个空列表并 `finish()`。
不确定是否长期保留时，用完就取消勾选。

## 4.2 v0.3（2026-11-10）：主空间图片分享打不开 —— 根因与实现
现象：分享图片给「微信 · 主空间」时，微信打开后又立刻退回相册；而 999/10–16 各空间正常，
「微信收藏 / 朋友圈 / 微信状态」也正常。
根因：微信 `ShareImgUI.onSceneEnd` 会再 `startActivity(MsgRetransmitUI, EXTRA_STREAM)`，
Android 15 的 `enforceRequireContentUriPermissionFromCallerOnIntentExtraStream` 要求
**发起方自己持有该 content:// 的读权**，于是 u0 微信抛
`SecurityException: UID 10360 does not have permission ... [user 0]` 并崩溃。
（收藏 / 朋友圈 / 微信状态 不经这条二次 startActivity，所以不触发；此为按崩溃栈的推断。）
实现（`Main.java`）：
1. `replay` 分支里，若 `tu == 0 && 目标包 == com.tencent.mm`，先把 `EXTRA_STREAM` 换成 `file://<真实路径>`，
   并把 `ClipData` 一起换成同一个 file URI（`toFileUri()` 用系统上下文查 MediaStore `_data`）。
2. 换成 file:// 后就**不再需要** `startActivityAsCaller`，直接 `startActivityAsUser(intent, UserHandle(0))`，
   避免 ColorOS 把 asc 那单改到 u999。
3. 其它空间保持 `startActivityAsCaller → startActivityAsUser` 不变（实测 u12 正常）。
新增日志标识：`replay file u0 <uri>`、`replay file-start u0 <intent>`、`toFileUri fail`。
实测：u0 `topResumedActivity=u0 com.tencent.mm/.ui.transmit.SelectConversationUI`。
已知限制：只处理单个 URI 的 `ACTION_SEND`；多选 `ACTION_SEND_MULTIPLE` 未处理；
路径必须在共享存储内（私有目录不适用）。

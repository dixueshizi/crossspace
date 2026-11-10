# crossspace —— ColorOS 跨空间拉起应用

在 ColorOS / OPPO / 一加的多开（MultiApp）与分身空间里，**用命令行精确地把某个应用的某个页面拉起在指定空间**，并让「别的应用要打开微信/支付宝」时弹出一个自建的跨空间选择器。

整套东西分成两层：

| 层 | 内容 | 需要什么 | 适用场景 |
|---|---|---|---|
| 脚本层 | `crossspace/crossspace.sh`（设备端）+ `crossspace.ps1`（Windows 封装） | adb（root 可选） | 自动化脚本、批量任务、调试时精确指定空间 |
| 模块层 | `crossspace/proto`：LSPosed 模块 + 自建选择器 | root + LSPosed | 第三方应用（支付、分享）拉起微信/支付宝时，让你选空间 |

脚本层不需要 root：以 `adb shell`（uid 2000）身份执行即可，因为 shell 持有跨用户交互权限。模块层要 hook `system_server`，必须有 LSPosed。

## 解决的问题

ColorOS 的多开空间（`999`、`10`–`16` 等）是多用户（`profile.CLONE`）实现的：

- `am start --user N` 能启动，但系统不允许 profile 用户成为**前台 user**，`am switch-user N` 必然失败；
- 系统选择器里同名应用的多个空间经常分不出来（ColorOS 用 `getMultiAppAlias()` 统一覆盖显示名），甚至会因为排序比较器不满足传递性直接崩掉；
- 从相册分享图片给「微信 · 某空间」时，Android 11+ 包可见性与 Android 15 的 URI 授权校验会把它打回或崩溃。

本项目给出的是这几点的实测解法与可复用脚本，细节全部写在 [docs/跨空间调用方案.md](docs/跨空间调用方案.md)。

## 目录结构

```
crossspace/
  crossspace.sh        # 设备端主脚本（sh，/system/bin/sh 可跑）
  crossspace.ps1       # Windows 侧薄封装：翻译参数 → adb
  apps.conf            # 应用别名 / 页面组件映射（可选）
  README.md            # 脚本层用法（一页纸）
  proto/               # LSPosed 跨空间选择器模块（Java 源码 + 手工打包脚本）
docs/
  跨空间调用方案.md     # 设计、踩坑与实测记录
legacy/
  wxspace.sh           # 早期必须 root 的版本，保留作参考
```

## 快速开始（脚本层）

```powershell
cd crossspace

# 一次性部署到手机 /data/local/tmp/crossspace
.\crossspace.ps1 -Deploy

# 查看有哪些空间、各自装没装微信/支付宝
.\crossspace.ps1 -Users

# 微信主界面 → 999 空间，并确认已显示在前台
.\crossspace.ps1 -User 999 -App wx -Page home -Foreground

# 支付宝主界面 → 10 号空间
.\crossspace.ps1 -User 10 -App alipay -Page home

# 回到主空间（发 KEYCODE_HOME，不切 user）
.\crossspace.ps1 -Back
```

设备端直接调用（Termux / 自动化 App 内）：

```sh
sh /data/local/tmp/crossspace/crossspace.sh 999 wx home
su -c 'sh /data/local/tmp/crossspace/crossspace.sh 10 alipay scan'
```

支持 `users` / `apps` / `env` / `home` / `<user> <app> <page>` / `all` / `component` 子命令，页面支持 `home`、`scan`、`sns`(微信)、`paycode`(支付宝) 或直接给完整组件名。完整参数与退出码见 [crossspace/README.md](crossspace/README.md)。

## 快速开始（模块层）

模块是手工打包的纯 Java 工程（不依赖 Gradle）：

```powershell
cd crossspace\proto
# 先把 Xposed API 放到 lib\api-82.jar（仓库不收录二进制，来源见下）
$env:ANDROID_JAR  = "<sdk>\platforms\android-35\android.jar"
$env:BUILD_TOOLS  = "<sdk>\build-tools\34.0.0"
$env:JAVA_HOME    = "<jdk 或 Android Studio jbr>"
powershell -File build.ps1

adb install --no-incremental -r crossspace-proto.apk
```

装完在 LSPosed 管理器里勾选模块，作用域至少选 `system`、`android`、`com.android.intentresolver`，然后重启。

> `lib/api-82.jar` 是 Xposed API 的编译期依赖，属于第三方二进制，仓库里不放，请自行从 Xposed / LSPosed 官方源获取后放到该路径。签名用的 `debug.keystore` 由 `build.ps1` 首次运行时自动生成，也不入库。

## 实测环境

ColorOS 15（OnePlus PJX110）SDK 35，脚本层用 `adb shell`（无 root）通过；模块层在 LSPosed 下通过，含支付宝 `alipays://`、微信 `weixin://`、图片 `ACTION_SEND` 分享到 u0 / 999 / 12 / 15 等空间。详见 docs 与 `crossspace/proto/README.md`。

## 已知边界（请先读）

- 模块只拦截**外部应用发起**、目标是 `WATCH_PKGS`（微信、支付宝）的 intent；其它应用要改 `Main.java` 里的常量再编译。
- 自建选择器**每次都会弹**，不实现「记住我的选择」。
- 分享回放延迟固定在 900ms：拉长到 2000ms 时后台启动会被 BAL 规则静默丢弃。
- 只处理单个 URI 的 `ACTION_SEND`，多选 `ACTION_SEND_MULTIPLE` 未支持；分享路径需在共享存储内。
- hook 在 `system_server` 内，异常均已 `try/catch` 包住，但仍可能拖慢解析或干扰系统自带多开选择器；不确定长期使用时请用完取消勾选。
- NFC「碰一碰」的 HCE 路径在 Intent 层拦不到，结论仍是静态推断。

## 贡献

欢迎提 issue / PR：新增应用与页面映射（`apps.conf` 或 `WATCH_PKGS`）、其它 ROM 的适配、分享与深链的边界情况、把工程迁到 Gradle 构建等，都很有价值。动手前请先读 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [docs/跨空间调用方案.md](docs/跨空间调用方案.md)，避免重复踩已知的坑。

## 免责声明

本项目是系统级调试/研究工具，会使用 adb、root、LSPosed 等能力，可能触发 ROM 的稳定性与风控行为。请只在**你自己拥有或明确授权**的设备上使用，遵守微信/支付宝等第三方应用的《用户协议》与当地法律；因使用本工具造成的任何后果由使用者自行承担。仓库不包含任何第三方应用的反编译产物、APK 或实机截图。

## License

MIT，见 [LICENSE](LICENSE)。

#!/system/bin/sh
# crossspace.sh —— ColorOS 跨空间（多用户）拉起应用
# 依赖：root（uid 0）或 adb shell（uid 2000），以及 am / pm / cmd（Android 8+）
#
# 身份说明：跨用户启动的权限检查在 system_server 侧按 callingUid 判定。
#   uid 0   -> 权限检查短路放行
#   uid 2000（com.android.shell，平台签名）-> 通常持有 INTERACT_ACROSS_USERS_FULL
# 所以“adb shell 直接跑”往往不需要 root；只有设备内（无 adb）执行时才需要 su。
#
# 用法：
#   crossspace.sh users                       列出用户 + 微信/支付宝安装情况
#   crossspace.sh apps                        列出当前应用名单（内置 + 配置文件）
#   crossspace.sh env                         打印版本/环境信息（排查用）
#   crossspace.sh home                        回到主空间（发 KEYCODE_HOME，不切 user）
#   crossspace.sh <user> <app> <page>         拉起指定空间里的 app 页面
#   crossspace.sh <user> component <pkg> <ComponentName> [am 参数...]
#   crossspace.sh all <app> <page> [10-30]    对所有非 0 用户批量
#
# 切前台（CROSSSPACE_FOREGROUND=1 / ps1 -Foreground）：
#   启动后确认顶层 Activity 已是目标 user，必要时自动点选 ColorOS 多开选择器。
#   注意：ColorOS 的空间都是 profile.CLONE（parentId=0），`am switch-user <N>`
#   会被系统拒绝（Failed to switch to user N），root 也一样；所以这里不切 user，
#   而是让目标空间的 Activity 显示在当前 display（实测 current user 保持 0）。
#
# <app>：别名 wx / wechat / alipay / ali，配置文件中自定义的别名，或直接写包名
# <page>：home / scan / sns(仅微信) / paycode(仅支付宝) / 组件全名
#
# 示例：
#   su -c 'sh /data/local/tmp/crossspace/crossspace.sh 999 wx home'
#   su -c 'sh /data/local/tmp/crossspace/crossspace.sh 10 alipay scan'
#   su -c 'sh /data/local/tmp/crossspace/crossspace.sh all wx home 10-30'
#
# 配置文件（可选，用来扩展 QQ / 抖音等）：
#   依次查找：脚本同目录 apps.conf → /data/local/tmp/crossspace/apps.conf
#   也可用 CROSSSPACE_CONF=/path/apps.conf 指定
#   格式：每行 别名=包名   或   别名:页面=完整组件名    # 开头为注释
#
# 环境变量：
#   CROSSSPACE_RANGE=10-30   all 模式只处理该区间（默认：所有非 0 用户）
#   CROSSSPACE_DRYRUN=1      只打印将执行的命令，不真正启动
#   CROSSSPACE_FORCE=1       跳过“目标空间是否安装该应用”的前置检查
#   CROSSSPACE_FOREGROUND=1  启动后确认/切换到目标空间前台（可能自动点选多开选择器）
#   CROSSSPACE_CONF=...      指定配置文件路径
#
# 退出码：
#   0  成功
#   64 参数错误
#   65 目标用户不存在
#   66 目标空间未安装该应用
#   67 找不到可启动组件
#   68 权限不足（既不是 root(0) 也不是 adb shell(2000)）
#   69 am 执行失败（系统拒绝 / 组件报错）
#   70 该页面不支持无感直达
#   71 配置文件格式错误
#   77 已启动但未能切到目标空间前台（多开选择器需手动选一次 / 锁屏）

PROG=crossspace
RANGE=${CROSSSPACE_RANGE:-}
DRYRUN=${CROSSSPACE_DRYRUN:-0}
FORCE=${CROSSSPACE_FORCE:-0}
FOREGROUND=${CROSSSPACE_FOREGROUND:-0}

E_USAGE=64
E_NO_USER=65
E_NO_APP=66
E_NO_COMP=67
E_NO_ROOT=68
E_AM_FAIL=69
E_UNSUPPORTED=70
E_CONF=71
E_FOREGROUND=77

log() { printf '%s\n' "$*" >&2; }
out() { printf '%s\n' "$*"; }
fail() { _c=$1; shift; log "ERROR[$_c] $*"; exit "$_c"; }

usage() {
  cat <<'EOF'
crossspace.sh —— 跨空间拉起应用（root 或 adb shell 均可）

  crossspace.sh users | apps | env | home | help
  crossspace.sh <user> <app> <page>
  crossspace.sh <user> component <pkg> <ComponentName> [am 额外参数...]
  crossspace.sh all <app> <page> [10-30]

app : wx | alipay | 自定义别名 | 包名
page: home | scan | sns(微信) | paycode(支付宝) | 完整组件名
home: 回到主空间（KEYCODE_HOME），用于从分身界面退回

环境变量：CROSSSPACE_RANGE=10-30  CROSSSPACE_DRYRUN=1  CROSSSPACE_FORCE=1
          CROSSSPACE_FOREGROUND=1（启动后确认/切到目标空间前台）
          CROSSSPACE_CONF=/path/apps.conf
退出码 0/64/65/66/67/68/69/70/71/77，详见脚本头部注释。
EOF
}

have() { command -v "$1" >/dev/null 2>&1; }

check_env() {
  have am || fail "$E_NO_ROOT" "找不到 am 命令，系统环境异常"
  uid=$(id -u 2>/dev/null)
  [ "$uid" = "0" ] || [ "$uid" = "2000" ] || fail "$E_NO_ROOT" \
    "当前 uid=$uid，既不是 root(0) 也不是 adb shell(2000)，无法跨用户启动。请用 root 或 adb shell 执行"
}

# ---------- 应用名单（内置 + 配置文件） ----------

# 内部用 '=' 作分隔符：mksh 会把 ${x%%|*} 里的 | 当成模式“或”，不能用 |
APP_PKGS="wx=com.tencent.mm
wechat=com.tencent.mm
alipay=com.eg.android.AlipayGphone
ali=com.eg.android.AlipayGphone"

PAGE_COMPS=""

load_conf() {
  conf=${CROSSSPACE_CONF:-}
  if [ -z "$conf" ]; then
    d=$(dirname "$0")
    [ -f "$d/apps.conf" ] && conf="$d/apps.conf"
  fi
  [ -z "$conf" ] && [ -f /data/local/tmp/crossspace/apps.conf ] && conf=/data/local/tmp/crossspace/apps.conf
  [ -z "$conf" ] && return 0
  [ -f "$conf" ] || fail "$E_CONF" "配置文件不存在: $conf"

  while IFS= read -r line || [ -n "$line" ]; do
    line=$(printf '%s' "$line" | sed 's/\r$//; s/^[[:space:]]*//; s/[[:space:]]*$//')
    case "$line" in ''|\#*) continue ;; esac
    case "$line" in *=*) : ;; *) fail "$E_CONF" "配置行缺少 '='：$line" ;; esac
    key=${line%%=*}
    val=${line#*=}
    key=$(printf '%s' "$key" | tr -d '[:space:]')
    val=$(printf '%s' "$val" | tr -d '[:space:]')
    [ -z "$key" ] && fail "$E_CONF" "配置行缺少键：$line"
    [ -z "$val" ] && fail "$E_CONF" "配置行缺少值：$line"
    case "$key" in
      *:*) PAGE_COMPS="$PAGE_COMPS
$key=$val" ;;
      *) APP_PKGS="$APP_PKGS
$key=$val" ;;
    esac
  done < "$conf"
}

app_pkg() {
  a=$1
  case "$a" in
    *.*) out "$a"; return 0 ;;   # 直接传包名
  esac
  oldifs=$IFS
  IFS='
'
  for e in $APP_PKGS; do
    if [ "${e%%=*}" = "$a" ]; then
      IFS=$oldifs
      out "${e#*=}"
      return 0
    fi
  done
  IFS=$oldifs
  return 1
}

page_override() {
  key="$1:$2"
  [ -n "$PAGE_COMPS" ] || return 1
  oldifs=$IFS
  IFS='
'
  for e in $PAGE_COMPS; do
    if [ "${e%%=*}" = "$key" ]; then
      IFS=$oldifs
      out "${e#*=}"
      return 0
    fi
  done
  IFS=$oldifs
  return 1
}

# ---------- 用户 / 包 ----------

all_user_ids() {
  pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p' | sort -n
}

user_exists() { pm list users 2>/dev/null | grep -q "UserInfo{$1:"; }

# 返回 0=已安装 1=未安装 2=无法判断（列表读不到）
app_state() {
  u=$1; pkg=$2
  [ "$FORCE" = "1" ] && return 0
  list=$(pm list packages --user "$u" 2>/dev/null)
  if [ -z "$list" ]; then
    log "WARN: 读不到 user $u 的包列表，跳过安装检查（可用 CROSSSPACE_FORCE=1 静默）"
    return 2
  fi
  printf '%s\n' "$list" | grep -q "^package:$pkg$" && return 0
  return 1
}

user_name() {
  pm list users 2>/dev/null | sed -n "s/.*UserInfo{$1:\([^:]*\).*/\1/p" | head -n 1
}

in_range() {
  u=$1
  [ -z "$RANGE" ] && return 0
  lo=${RANGE%%-*}
  hi=${RANGE#*-}
  [ "$u" -ge "$lo" ] 2>/dev/null || return 1
  [ "$u" -le "$hi" ] 2>/dev/null || return 1
  return 0
}

# ---------- 组件解析 ----------

resolve_launcher() {
  u=$1; pkg=$2

  # 策略 1：resolve-activity + -p（多数 ColorOS 版本可用）
  c=$(cmd package resolve-activity --brief --user "$u" \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        -p "$pkg" 2>/dev/null | tail -n 1)
  case "$c" in
    "$pkg"/*) out "$c"; return 0 ;;
  esac

  # 策略 2：包名作为后缀参数（部分 Android 版本可用）
  c=$(cmd package resolve-activity --brief --user "$u" "$pkg" 2>/dev/null | tail -n 1)
  case "$c" in
    "$pkg"/*) out "$c"; return 0 ;;
  esac

  # 策略 3：query-activities 全量查询后按包名过滤
  c=$(cmd package query-activities --brief --user "$u" \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER 2>/dev/null | grep "^$pkg/" | head -n 1)
  case "$c" in
    "$pkg"/*) out "$c"; return 0 ;;
  esac

  fail "$E_NO_COMP" "user $u 解析不到 $pkg 的启动页；可显式传组件：crossspace.sh $u component $pkg <pkg/Activity>，或在 apps.conf 加 <别名>:home=<组件>"
}

comp_exists() {
  u=$1; c=$2
  r=$(cmd package resolve-activity --brief --user "$u" -n "$c" 2>/dev/null | tail -n 1)
  case "$r" in
    ""|No\ activity*|no\ activity*|Error*|error*) : ;;
    */*) return 0 ;;
  esac
  # 兜底：dumpsys 里查组件声明，不受 exported / 包可见性影响
  pkg=${c%%/*}
  cls=${c#*/}
  case "$cls" in
    .*) cls="$pkg$cls" ;;
  esac
  dumpsys package "$pkg" 2>/dev/null | grep -F -q "$cls" && return 0
  dumpsys package --user "$u" "$pkg" 2>/dev/null | grep -F -q "$cls" && return 0
  return 1
}

try_candidates() {
  u=$1; cands=$2; pkg=$3; page=$4
  oldifs=$IFS
  IFS='
'
  for c in $cands; do
    if comp_exists "$u" "$c"; then
      IFS=$oldifs
      out "$c"
      return 0
    fi
  done
  IFS=$oldifs
  return 1
}

pick_component() {
  u=$1; pkg=$2; page=$3; alias=$4

  ov=$(page_override "$alias" "$page") || ov=$(page_override "$pkg" "$page")
  if [ -n "${ov:-}" ]; then
    case "$ov" in
      uri:*) out "$ov"; return 0 ;;
      *)
        if comp_exists "$u" "$ov"; then out "$ov"; return 0; fi ;;
    esac
  fi

  case "$page" in
    home|main|launcher)
      resolve_launcher "$u" "$pkg"
      ;;
    scan)
      if [ "$pkg" = "com.tencent.mm" ]; then
        cands="com.tencent.mm/com.tencent.mm.plugin.scanner.ui.BaseScanUI
com.tencent.mm/com.tencent.mm.plugin.scanner.ui.ScannerUI
com.tencent.mm/com.tencent.mm.ui.scanner.BaseScanUI"
        try_candidates "$u" "$cands" "$pkg" "$page" || \
          fail "$E_NO_COMP" "user $u 的微信未找到扫一扫组件（版本差异），可用 component 显式指定或写进 apps.conf"
      elif [ "$pkg" = "com.eg.android.AlipayGphone" ]; then
        # ColorOS 15 实测：支付宝没有独立的 Scan Activity，只有 ShareScanQRDispenseActivity
        cands="com.eg.android.AlipayGphone/com.alipay.android.phone.scancode.camera.ScanActivity
com.eg.android.AlipayGphone/com.alipay.android.phone.scancode.ui.ScanActivity
com.eg.android.AlipayGphone/com.alipay.android.phone.scancode.activity.ScanActivity"
        try_candidates "$u" "$cands" "$pkg" "$page" || \
          fail "$E_UNSUPPORTED" "支付宝没有独立的扫一扫 Activity（ColorOS 15 实测）。可改用 scheme：crossspace.sh $u alipay \"uri:alipays://platformapi/startapp?appId=10000007\""
      else
        fail "$E_NO_COMP" "未知应用的 scan 页面：$pkg"
      fi
      ;;
    sns|moments)
      cands="com.tencent.mm/com.tencent.mm.plugin.sns.ui.SnsTimeLineUI
com.tencent.mm/com.tencent.mm.plugin.sns.ui.SnsTimeLineUI2"
      try_candidates "$u" "$cands" "$pkg" "$page" || \
        fail "$E_NO_COMP" "user $u 的微信未找到朋友圈组件（版本差异），可用 component 显式指定"
      ;;
    paycode|pay)
      fail "$E_UNSUPPORTED" "支付宝付款码没有可直达的公开组件（ColorOS 15 实测）。通常只能先起主界面再手动点，或自行确认可用 scheme 后写进 apps.conf"
      ;;
    chat|conversation)
      fail "$E_UNSUPPORTED" "微信/支付宝没有公开的“直达指定会话”入口；请用 component 透传或改用无障碍点击"
      ;;
    */*)
      comp_exists "$u" "$page" || fail "$E_NO_COMP" "user $u 组件不存在: $page"
      out "$page"
      ;;
    *)
      fail "$E_USAGE" "未知页面: $page（支持 home/scan/sns/paycode 或完整组件名）"
      ;;
  esac
}

# ---------- 启动 ----------

am_start() {
  u=$1; c=$2; shift 2
  case "$c" in
    uri:*)
      url=${c#uri:}
      if [ "$DRYRUN" = "1" ]; then
        out "[dryrun] am start --user $u -a android.intent.action.VIEW -d $url $*"
        return 0
      fi
      r=$(am start --user "$u" -a android.intent.action.VIEW -d "$url" "$@" 2>&1)
      ;;
    *)
      if [ "$DRYRUN" = "1" ]; then
        out "[dryrun] am start --user $u -n $c $*"
        return 0
      fi
      r=$(am start --user "$u" -n "$c" "$@" 2>&1)
      ;;
  esac
  rc=$?
  case "$r" in
    *"Permission Denial"*)
      fail "$E_AM_FAIL" "user $u 启动被系统拒绝（跨用户权限）。若当前是 shell 身份，请改用 su 执行：$r" ;;
    *Exception*|*"Error:"*|*"Error type"*)
      fail "$E_AM_FAIL" "user $u 启动失败：$r" ;;
  esac
  [ "$rc" -ne 0 ] && fail "$E_AM_FAIL" "am 退出码 $rc：$r"
  out "$r"
}

# ---------- 前台确认 ----------
# ColorOS 的多开/分身空间都是 profile.CLONE（parentId=0），系统不允许 profile 成为
# 前台 user，所以 `am switch-user <N>` 对它们必然失败（root 也无法绕过 user type 限制）。
# 但 `am start --user N` 起的 Activity 会直接显示在当前 display 上（current user 仍是 0）。
# 因此“切前台”这里做的是：确认顶层 Activity 已经是 uN，必要时点掉 ColorOS 多开选择器。

top_activity() { dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity; }

# 在 ColorOS 多开选择器里点选目标空间（分身项文本形如 “<用户名>.999”）
pick_in_chooser() {
  u=$1
  have uiautomator || return 1
  f=/data/local/tmp/crossspace_ui.xml
  uiautomator dump "$f" >/dev/null 2>&1 || return 1
  line=$(tr '>' '\n' < "$f" 2>/dev/null | grep 'android:id/text1' | grep -F ".$u\"" | head -n 1)
  [ -n "$line" ] || return 1
  b=$(printf '%s\n' "$line" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  [ -n "$b" ] || return 1
  set -- $b
  input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  return 0
}

bring_foreground() {
  u=$1
  input keyevent 224 >/dev/null 2>&1   # KEYCODE_WAKEUP：只点亮屏幕，不解锁
  i=0
  while [ $i -lt 8 ]; do
    top=$(top_activity)
    case "$top" in
      *" u$u "*)
        out "已在目标空间前台：$top"
        return 0 ;;
      *ChooserActivity*|*ResolverActivity*)
        if pick_in_chooser "$u"; then sleep 2; continue; fi ;;
    esac
    i=$((i + 1))
    sleep 1
  done
  fail "$E_FOREGROUND" "已启动，但屏幕没有切到 user $u。若弹出了 ColorOS 多开选择器：请手动点选对应空间并勾选“记住我的选择”（只需一次），之后 -Foreground 可自动直达；锁屏时请先解锁再重试"
}

do_home() {
  input keyevent 3
  sleep 1
  top=$(top_activity)
  case "$top" in
    *" u0 "*) out "已回到主空间：$top" ;;
    *) out "已发送 HOME；当前前台：$top（若未回到主空间，再按一次 HOME 或解锁后重试）" ;;
  esac
}

do_component() {
  u=$1; pkg=$2; c=$3; shift 3
  user_exists "$u" || fail "$E_NO_USER" "用户 $u 不存在（crossspace.sh users 查看现有空间）"
  app_state "$u" "$pkg"
  [ $? -eq 1 ] && fail "$E_NO_APP" "用户 $u（$(user_name "$u")）未安装 $pkg，请先在该空间安装该应用"
  comp_exists "$u" "$c" || fail "$E_NO_COMP" "user $u 组件不存在或不可解析：$c"
  am_start "$u" "$c" "$@"
  [ "$FOREGROUND" = "1" ] && [ "$DRYRUN" != "1" ] && bring_foreground "$u"
}

do_user() {
  u=$1; alias=$2; page=$3

  user_exists "$u" || fail "$E_NO_USER" "用户 $u 不存在（crossspace.sh users 查看现有空间）"

  pkg=$(app_pkg "$alias") || fail "$E_USAGE" "未知应用别名：$alias（crossspace.sh apps 查看名单，或直接传包名）"

  app_state "$u" "$pkg"
  [ $? -eq 1 ] && fail "$E_NO_APP" "用户 $u（$(user_name "$u")）未安装 $pkg，请先在该空间安装该应用"

  case "$page" in
    uri:*)
      c=$page ;;
    *)
      c=$(pick_component "$u" "$pkg" "$page" "$alias") || exit $? ;;
  esac
  am_start "$u" "$c"
  [ "$FOREGROUND" = "1" ] && [ "$DRYRUN" != "1" ] && bring_foreground "$u"
}

# ---------- 展示命令 ----------

do_users() {
  printf '%-6s %-8s %-10s %s\n' "USER" "微信" "支付宝" "名称"
  for u in $(all_user_ids); do
    app_state "$u" "com.tencent.mm"
    case $? in
      0) w="有" ;;
      1) w="无" ;;
      *) w="?" ;;
    esac
    app_state "$u" "com.eg.android.AlipayGphone"
    case $? in
      0) a="有" ;;
      1) a="无" ;;
      *) a="?" ;;
    esac
    printf '%-6s %-8s %-10s %s\n' "$u" "$w" "$a" "$(user_name "$u")"
  done
}

do_apps() {
  printf '%-12s %s\n' "ALIAS" "PACKAGE"
  oldifs=$IFS
  IFS='
'
  for e in $APP_PKGS; do
    printf '%-12s %s\n' "${e%%=*}" "${e#*=}"
  done
  IFS=$oldifs
  if [ -n "$PAGE_COMPS" ]; then
    printf '\n页面映射:\n'
    IFS='
'
    for e in $PAGE_COMPS; do
      printf '  %s -> %s\n' "${e%%=*}" "${e#*=}"
    done
    IFS=$oldifs
  fi
}

do_env() {
  out "uid=$(id -u)"
  out "sdk=$(getprop ro.build.version.sdk)"
  out "display=$(getprop ro.build.display.id)"
  out "brand=$(getprop ro.product.brand) manufacturer=$(getprop ro.product.manufacturer)"
  out "users=$(all_user_ids | tr '\n' ' ')"
  out "range=${RANGE:-<all non-0 users>}"
  out "dryrun=$DRYRUN force=$FORCE foreground=$FOREGROUND"
  out "conf=$( [ -n "${CROSSSPACE_CONF:-}" ] && printf '%s' "$CROSSSPACE_CONF" || printf '(auto)')"
}

do_all() {
  alias=$1; page=$2
  [ $# -ge 3 ] && RANGE=$3
  n=0; ok=0; bad=0
  FOREGROUND=0   # 批量模式逐个切前台没有意义，也会打断用户
  for u in $(all_user_ids); do
    [ "$u" = "0" ] && continue
    in_range "$u" || continue
    n=$((n + 1))
    if ( do_user "$u" "$alias" "$page" ) >/dev/null 2>&1; then
      ok=$((ok + 1))
      log "user $u: OK"
    else
      rc=$?
      bad=$((bad + 1))
      log "user $u: 跳过（退出码 $rc）"
    fi
  done
  out "完成：共 $n 个用户，成功 $ok，失败 $bad"
  [ "$bad" -eq 0 ] || exit "$E_AM_FAIL"
}

# ---------- 入口 ----------

main() {
  case "${1:-}" in
    -h|--help|help) usage; exit 0 ;;
  esac

  check_env
  [ $# -ge 1 ] || { usage; exit "$E_USAGE"; }

  case "$1" in
    users|list) load_conf; do_users; exit 0 ;;
    apps) load_conf; do_apps; exit 0 ;;
    env|doctor) load_conf; do_env; exit 0 ;;
    home|back|main) do_home; exit 0 ;;
    all)
      load_conf
      [ $# -ge 3 ] || { usage; exit "$E_USAGE"; }
      do_all "$2" "$3" ${4:+"$4"}; exit $? ;;
  esac

  u=$1
  case "$u" in
    [0-9]*) : ;;
    *) fail "$E_USAGE" "第一个参数应为用户 ID 或 all，收到：$u" ;;
  esac

  load_conf

  case "$2" in
    component)
      [ $# -ge 4 ] || fail "$E_USAGE" "用法：crossspace.sh <user> component <pkg> <ComponentName> [am 参数...]"
      pkg=$3; c=$4; shift 4
      do_component "$u" "$pkg" "$c" "$@" ;;
    *)
      [ $# -ge 3 ] || { usage; exit "$E_USAGE"; }
      do_user "$u" "$2" "$3" ;;
  esac
}

main "$@"

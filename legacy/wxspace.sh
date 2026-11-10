#!/system/bin/sh
# wxspace.sh —— ColorOS 跨空间（多用户）拉起应用（早期版本，必须 root）
# 依赖：root（uid 0，KernelSU/Magisk 均可）、am、pm、cmd（Android 8+）
#
# 说明：这是 crossspace.sh 的前身，只认 root 身份，应用名单用 '|' 作分隔符。
#       后续确认 shell 身份已可跨用户启动，且 mksh 会把 ${x%%|*} 里的 | 当模式“或”，
#       所以改成了 crossspace/crossspace.sh（'=' 分隔 + shell/root 双身份）。
#       保留此文件仅为对照与回溯，新用法请直接用 crossspace/crossspace.sh。
#
# 用法：
#   wxspace.sh users                          列出用户 + 微信/支付宝安装情况
#   wxspace.sh apps                           列出当前应用名单（内置 + 配置文件）
#   wxspace.sh env                            打印版本/环境信息（排查用）
#   wxspace.sh <user> <app> <page>           拉起指定空间里的 app 页面
#   wxspace.sh <user> component <pkg> <Component> [am 参数...]
#   wxspace.sh all <app> <page>              对所有非 0 用户批量
#
# <app> 为别名：wx（微信）、alipay（支付宝），或配置文件里自定义的别名
#             也可以直接传包名（含 '.' 时按包名处理）
# <page> 为 home / scan / sns(仅微信) / paycode(仅支付宝) / 组件全名
#
# 示例：
#   su -c 'sh /data/local/tmp/wxspace/wxspace.sh 10 wx home'
#   su -c 'sh /data/local/tmp/wxspace/wxspace.sh 999 alipay scan'
#   su -c 'sh /data/local/tmp/wxspace/wxspace.sh all wx home'
#
# 配置文件（可选，用来扩展 QQ/抖音等）：
#   默认读取脚本同目录的 apps.conf，其次 /data/local/tmp/wxspace/apps.conf
#   也可用 WXSPACE_CONF=/path/apps.conf 指定
#   格式：每行 别名=包名       或   别名:页面=完整组件名
#   # 开头为注释
#
# 环境变量：
#   WXSPACE_RANGE=10-40   all 模式只处理该区间（默认：所有非 0 用户）
#   WXSPACE_DRYRUN=1      只打印将执行的命令，不真正启动
#   WXSPACE_CONF=...      指定配置文件路径
#
# 退出码：
#   0  成功
#   64 参数错误
#   65 目标用户不存在
#   66 目标空间未安装该应用
#   67 找不到可启动组件
#   68 没有 root 权限
#   69 am 执行失败（系统拒绝/组件报错）
#   70 该页面不支持无感直达
#   71 配置文件格式错误

PROG=wxspace
RANGE=${WXSPACE_RANGE:-}
DRYRUN=${WXSPACE_DRYRUN:-0}

E_USAGE=64
E_NO_USER=65
E_NO_APP=66
E_NO_COMP=67
E_NO_ROOT=68
E_AM_FAIL=69
E_UNSUPPORTED=70
E_CONF=71

log() { printf '%s\n' "$*" >&2; }
out() { printf '%s\n' "$*"; }
fail() { code=$1; shift; log "ERROR[$code] $*"; exit "$code"; }

usage() {
  cat <<'EOF'
wxspace.sh —— 跨空间拉起应用（需要 root）

  wxspace.sh users | apps | env | help
  wxspace.sh <user> <app> <page>
  wxspace.sh <user> component <pkg> <ComponentName> [am 额外参数...]
  wxspace.sh all <app> <page>

app: wx | alipay | 自定义别名 | 包名
page: home | scan | sns(微信) | paycode(支付宝) | 完整组件名

环境变量：WXSPACE_RANGE=10-40  WXSPACE_DRYRUN=1  WXSPACE_CONF=/path/apps.conf
退出码 0/64/65/66/67/68/69/70/71，详见脚本头部注释。
EOF
}

have() { command -v "$1" >/dev/null 2>&1; }

check_root() {
  [ "$(id -u 2>/dev/null)" = "0" ] || fail "$E_NO_ROOT" \
    "需要 root。请用 su -c 调用：su -c 'sh $0 $*'"
  have am || fail "$E_NO_ROOT" "找不到 am 命令"
}

# ---------- 应用名单（内置 + 配置文件） ----------

APP_PKGS="wx|com.tencent.mm
wechat|com.tencent.mm
alipay|com.eg.android.AlipayGphone
ali|com.eg.android.AlipayGphone"

PAGE_COMPS=""

load_conf() {
  conf=${WXSPACE_CONF:-}
  if [ -z "$conf" ]; then
    d=$(dirname "$0")
    [ -f "$d/apps.conf" ] && conf="$d/apps.conf"
  fi
  [ -z "$conf" ] && [ -f /data/local/tmp/wxspace/apps.conf ] && conf=/data/local/tmp/wxspace/apps.conf
  [ -z "$conf" ] && return 0
  [ -f "$conf" ] || fail "$E_CONF" "配置文件不存在: $conf"

  while IFS= read -r line || [ -n "$line" ]; do
    line=$(printf '%s' "$line" | sed 's/\r$//; s/^[[:space:]]*//; s/[[:space:]]*$//')
    case "$line" in ''|\#*) continue ;; esac
    case "$line" in *=*) : ;; *) fail "$E_CONF" "配置行缺少 '=': $line" ;; esac
    key=${line%%=*}
    val=${line#*=}
    key=$(printf '%s' "$key" | tr -d ' ')
    val=$(printf '%s' "$val" | tr -d ' ')
    [ -z "$key" ] && fail "$E_CONF" "配置行缺少键: $line"
    [ -z "$val" ] && fail "$E_CONF" "配置行缺少值: $line"
    case "$key" in
      *:*) PAGE_COMPS="$PAGE_COMPS
$key|$val" ;;
      *) APP_PKGS="$APP_PKGS
$key|$val" ;;
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
    if [ "${e%%|*}" = "$a" ]; then
      IFS=$oldifs
      out "${e#*|}"
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
    if [ "${e%%|*}" = "$key" ]; then
      IFS=$oldifs
      out "${e#*|}"
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

app_in_user() {
  pm list packages --user "$1" 2>/dev/null | grep -q "^package:$2$"
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

  # 策略 1：resolve-activity + -p（较新 ColorOS 可用）
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

  fail "$E_NO_COMP" "user $u 解析不到 $pkg 的启动页；请显式传组件或在 apps.conf 写 <别名>:home=<组件>"
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
  dumpsys package "$pkg" 2>/dev/null | grep -q "$cls" && return 0
  dumpsys package --user "$u" "$pkg" 2>/dev/null | grep -q "$cls" && return 0
  return 1
}

pick_component() {
  u=$1; pkg=$2; page=$3; alias=$4

  ov=$(page_override "$alias" "$page") || ov=$(page_override "$pkg" "$page")
  if [ -n "${ov:-}" ] && comp_exists "$u" "$ov"; then
    out "$ov"; return 0
  fi

  case "$page" in
    home|main|launcher)
      c=$(resolve_launcher "$u" "$pkg")
      case "$c" in
        ""|No\ activity*) fail "$E_NO_COMP" "user $u 解析不到 $pkg 的启动页" ;;
      esac
      out "$c"
      ;;
    scan)
      if [ "$pkg" = "com.tencent.mm" ]; then
        cands="com.tencent.mm/com.tencent.mm.plugin.scanner.ui.BaseScanUI
com.tencent.mm/com.tencent.mm.plugin.scanner.ui.ScannerUI
com.tencent.mm/com.tencent.mm.ui.scanner.BaseScanUI"
      elif [ "$pkg" = "com.eg.android.AlipayGphone" ]; then
        cands="com.eg.android.AlipayGphone/com.alipay.android.phone.scancode.camera.ScanActivity
com.eg.android.AlipayGphone/com.alipay.android.phone.scancode.ui.ScanActivity
com.eg.android.AlipayGphone/com.alipay.android.phone.scancode.activity.ScanActivity"
      else
        cands=""
      fi
      try_candidates "$u" "$cands" "$pkg" "$page"
      ;;
    sns|moments)
      cands="com.tencent.mm/com.tencent.mm.plugin.sns.ui.SnsTimeLineUI
com.tencent.mm/com.tencent.mm.plugin.sns.ui.SnsTimeLineUI2"
      try_candidates "$u" "$cands" "$pkg" "$page"
      ;;
    paycode|pay)
      cands="com.eg.android.AlipayGphone/com.alipay.android.phone.paycode.PayCodeActivity
com.eg.android.AlipayGphone/com.alipay.android.phone.paycode.ui.PayCodeActivity
com.eg.android.AlipayGphone/com.alipay.android.phone.scancode.paycode.PayCodeActivity"
      try_candidates "$u" "$cands" "$pkg" "$page"
      ;;
    chat|conversation)
      fail "$E_UNSUPPORTED" "微信/支付宝没有公开的'直达指定会话'入口；请用 component 透传或无障碍点击"
      ;;
    */*)
      comp_exists "$u" "$page" || fail "$E_NO_COMP" "user $u 组件不存在: $page"
      out "$page"
      ;;
    *)
      fail "$E_USAGE" "未知页面: $page（支持 home/scan/sns/paycode/完整组件名）"
      ;;
  esac
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
  fail "$E_NO_COMP" "user $u 的 $pkg 未找到 $page 组件（版本差异？）。可用 component 显式指定，或写进 apps.conf：<别名>:$page=<组件>"
}

# ---------- 启动 ----------

am_start() {
  u=$1; c=$2; shift 2
  if [ "$DRYRUN" = "1" ]; then
    out "[dryrun] am start --user $u -n $c $*"
    return 0
  fi
  r=$(am start --user "$u" -n "$c" "$@" 2>&1)
  rc=$?
  case "$r" in
    *"Permission Denial"*|*Exception*|*"Error:"*)
      fail "$E_AM_FAIL" "user $u 启动失败: $r" ;;
  esac
  [ "$rc" -ne 0 ] && fail "$E_AM_FAIL" "am 退出码 $rc: $r"
  out "$r"
}

do_user() {
  u=$1; alias=$2; page=$3; shift 3

  user_exists "$u" || fail "$E_NO_USER" "用户 $u 不存在（wxspace.sh users 查看）"

  if [ "$alias" = "component" ]; then
    pkg=$1; c=$2; shift 2
    app_in_user "$u" "$pkg" || fail "$E_NO_APP" "用户 $u（$(user_name "$u")）未安装 $pkg，请先在该空间安装"
    comp_exists "$u" "$c" || fail "$E_NO_COMP" "user $u 组件不存在/不可解析: $c"
    am_start "$u" "$c" "$@"
    return
  fi

  pkg=$(app_pkg "$alias") || fail "$E_USAGE" "未知应用别名: $alias（wxspace.sh apps 查看名单，或直接传包名）"
  app_in_user "$u" "$pkg" || fail "$E_NO_APP" "用户 $u（$(user_name "$u")）未安装 $pkg，请先在该空间安装该应用"

  c=$(pick_component "$u" "$pkg" "$page" "$alias") || exit $?
  am_start "$u" "$c"
}

# ---------- 展示命令 ----------

do_users() {
  printf '%-6s %-10s %-10s %s\n' "USER" "微信" "支付宝" "名称"
  for u in $(all_user_ids); do
    w="无"; a="无"
    app_in_user "$u" "com.tencent.mm" && w="有"
    app_in_user "$u" "com.eg.android.AlipayGphone" && a="有"
    printf '%-6s %-10s %-10s %s\n' "$u" "$w" "$a" "$(user_name "$u")"
  done
}

do_apps() {
  printf '%-10s %s\n' "ALIAS" "PACKAGE"
  oldifs=$IFS
  IFS='
'
  for e in $APP_PKGS; do
    printf '%-10s %s\n' "${e%%|*}" "${e#*|}"
  done
  IFS=$oldifs
  if [ -n "$PAGE_COMPS" ]; then
    printf '\n页面映射:\n'
    IFS='
'
    for e in $PAGE_COMPS; do
      printf '  %s -> %s\n' "${e%%|*}" "${e#*|}"
    done
    IFS=$oldifs
  fi
}

do_env() {
  out "uid=$(id -u)"
  out "sdk=$(getprop ro.build.version.sdk)"
  out "display=$(getprop ro.build.display.id)"
  out "brand=$(getprop ro.product.brand) manufacturer=$(getprop ro.product.manufacturer)"
  out "range=${RANGE:-<all non-0 users>}"
  out "dryrun=$DRYRUN"
  out "conf=$( [ -n "${WXSPACE_CONF:-}" ] && printf '%s' "$WXSPACE_CONF" || printf '(auto)')"
}

do_all() {
  alias=$1; page=$2
  n=0; ok=0; bad=0
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
  check_root
  [ $# -ge 1 ] || { usage; exit "$E_USAGE"; }

  case "$1" in
    users|list) load_conf; do_users; exit 0 ;;
    apps) load_conf; do_apps; exit 0 ;;
    env|doctor) load_conf; do_env; exit 0 ;;
    -h|--help|help) usage; exit 0 ;;
    all)
      load_conf
      [ $# -ge 3 ] || { usage; exit "$E_USAGE"; }
      do_all "$2" "$3"; exit $? ;;
  esac

  [ $# -ge 3 ] || { usage; exit "$E_USAGE"; }
  u=$1
  case "$u" in
    [0-9]*) : ;;
    *) fail "$E_USAGE" "第一个参数应是用户 ID 或 all，收到: $u" ;;
  esac

  load_conf

  page=$3
  shift 3
  do_user "$u" "$2" "$page" "$@"
}

main "$@"

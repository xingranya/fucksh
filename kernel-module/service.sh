#!/system/bin/sh
############################################################
# SystemHelper 出站阻断  v1.1
#   目标: com.mobiletools.systemhelper (联通 DM 上报组件)
#   手段: iptables/ip6tables 按 uid 匹配 owner 模块 -> REJECT
#   v1.1: uid 探测改为多路(优先 pm -U)，并加入重试；因为
#         早期阶段 stat /data/data 会被 SELinux 拒绝、
#         dumpsys 也可能因 system_server 未就绪而失败。
############################################################

MODDIR=${0%/*}
LOG="$MODDIR/log.txt"
PKG=com.mobiletools.systemhelper

# 自动探测失败时可手动写死 uid（本机实测为 10100）
UID_OVERRIDE=""

log() { echo "[$(date '+%F %T')] $*" >> "$LOG" 2>/dev/null; }
have() { command -v "$1" >/dev/null 2>&1; }

# 从 "package:com.x uid:10100" 格式里提取 uid
parse_pm() {
  grep -F "package:$PKG " | sed -n 's/.*uid:\([0-9]\{1,\}\).*/\1/p' | head -1
}

detect_uid() {
  u=""
  [ -n "$UID_OVERRIDE" ] && { echo "$UID_OVERRIDE"; return; }

  # 1) pm list packages -U （Android 11+ 实测可用）
  have pm && u=$(pm list packages -U 2>/dev/null | parse_pm)
  # 2) cmd package list packages -U
  [ -z "$u" ] && have cmd && u=$(cmd package list packages -U 2>/dev/null | parse_pm)
  # 3) dumpsys（system_server 就绪后才可用）
  [ -z "$u" ] && u=$(dumpsys package "$PKG" 2>/dev/null | grep -o 'userId=[0-9]*' | head -1 | cut -d= -f2)
  # 4) 老系统的 packages.list
  if [ -z "$u" ] && [ -f /data/system/packages.list ]; then
    u=$(grep "^$PKG " /data/system/packages.list 2>/dev/null | awk '{print $2}')
  fi
  # 5) 直接看数据目录宿主
  [ -z "$u" ] && u=$(stat -c %u "/data/user/0/$PKG" 2>/dev/null)
  [ -z "$u" ] && u=$(stat -c %u "/data/data/$PKG" 2>/dev/null)
  [ -z "$u" ] && u=$(ls -ldn "/data/user/0/$PKG" 2>/dev/null | awk '{print $3}')

  # 合法性校验：普通应用 uid 应 >= 10000
  case "$u" in
    ''|*[!0-9]*) u="" ;;
  esac
  if [ -n "$u" ] && [ "$u" -lt 10000 ]; then u=""; fi
  echo "$u"
}

apply_rules() {
  uid="$1"
  if have iptables; then
    iptables -D OUTPUT -m owner --uid-owner "$uid" -j REJECT 2>/dev/null
    iptables -I OUTPUT -m owner --uid-owner "$uid" -j REJECT 2>/dev/null \
      && log "iptables: OUTPUT reject uid=$uid" || log "iptables: 添加失败(uid=$uid)"
  else
    log "WARN: 没有 iptables"
  fi
  if have ip6tables; then
    ip6tables -D OUTPUT -m owner --uid-owner "$uid" -j REJECT 2>/dev/null
    ip6tables -I OUTPUT -m owner --uid-owner "$uid" -j REJECT 2>/dev/null \
      && log "ip6tables: OUTPUT reject uid=$uid" || log "ip6tables: 添加失败(uid=$uid)"
  else
    log "WARN: 没有 ip6tables，IPv6 未拦"
  fi
}

rules_ok() {
  uid="$1"
  if have iptables; then
    iptables -S OUTPUT 2>/dev/null | grep -q -- "--uid-owner $uid" || return 1
  fi
  if have ip6tables; then
    ip6tables -S OUTPUT 2>/dev/null | grep -q -- "--uid-owner $uid" || return 1
  fi
  return 0
}

log "service: waiting for boot_completed"
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do
  sleep 5; i=$((i+1))
done
sleep 20   # 给 system_server / PackageManager 留出就绪时间

# 最多重试 20 次（约 5 分钟）
UID_SYS=""
n=0
while [ $n -lt 20 ]; do
  UID_SYS=$(detect_uid)
  [ -n "$UID_SYS" ] && break
  n=$((n+1))
  log "uid 探测第 $n 次失败，15 秒后重试"
  sleep 15
done

if [ -z "$UID_SYS" ]; then
  log "ERROR: 无法确定 $PKG 的 uid。请在 service.sh 顶部把 UID_OVERRIDE 改成实际 uid 后重新刷入。"
  exit 1
fi
log "target $PKG uid=$UID_SYS"

apply_rules "$UID_SYS"

# 初始化与 Xposed 模块共享的配置目录（两侧都能读写）
init_shared() {
  XPDIR=/storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker
  mkdir -p "$XPDIR" 2>/dev/null
  if [ ! -f "$XPDIR/config.conf" ]; then
    cat > "$XPDIR/config.conf" <<'CFG'
# SystemHelperBlocker 开关（1=启用，0=关闭）
# 修改后需要重启目标进程才会生效：
#   su -c "am force-stop com.mobiletools.systemhelper"
#   su -c "am startservice -n com.mobiletools.systemhelper/com.mobiletools.systemhelper.service.RegisterHelperService"
BLOCK_EGRESS=1
BLOCK_COLLECT=1
BLOCK_LOCATION=1
BLOCK_DB=1
WIPE_DB=1
CFG
    log "shared: config.conf created"
  else
    log "shared: config.conf exists"
  fi
}

init_shared

# 守护：netd 重启/网络切换可能清空自定义规则
while true; do
  sleep 60
  if ! rules_ok "$UID_SYS"; then
    log "rules missing -> re-apply"
    apply_rules "$UID_SYS"
  fi
done

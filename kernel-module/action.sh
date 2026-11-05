#!/system/bin/sh
############################################################
# KernelSU 模块「操作」按钮 -> 一键体检（含自动修复）
# 在 KernelSU 管理器: 模块 -> sh_blocker -> 操作/运行
############################################################
MODDIR=${0%/*}
PKG=com.mobiletools.systemhelper
MLOG="$MODDIR/log.txt"

line() { echo "────────────────────────────────────"; }

echo "╔══════════════════════════════════════╗"
echo "║  SystemHelper 拦截状态体检            ║"
echo "╚══════════════════════════════════════╝"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"

# ---------- 1. uid ----------
UID_SYS=$(pm list packages -U 2>/dev/null | grep -F "package:$PKG " | sed -n 's/.*uid:\([0-9]\{1,\}\).*/\1/p' | head -1)
[ -z "$UID_SYS" ] && UID_SYS=$(dumpsys package "$PKG" 2>/dev/null | grep -o 'userId=[0-9]*' | head -1 | cut -d= -f2)
line
echo "[1] 目标进程"
if [ -n "$UID_SYS" ]; then
  echo "    包名 : $PKG"
  echo "    uid  : $UID_SYS"
else
  echo "    !! 取不到 uid（应用可能已被卸载）"
fi
PID=$(pidof "$PKG" 2>/dev/null)
echo "    pid  : ${PID:-未运行}"

# ---------- 2. 内核规则（含自动修复）----------
line
echo "[2] 内核层规则（iptables / ip6tables）"
FIXED=0
if [ -n "$UID_SYS" ]; then
  if iptables -S OUTPUT 2>/dev/null | grep -q -- "--uid-owner $UID_SYS"; then
    echo "    IPv4 : OK"
  else
    iptables -I OUTPUT -m owner --uid-owner "$UID_SYS" -j REJECT 2>/dev/null \
      && { echo "    IPv4 : 缺失 -> 已自动补上"; FIXED=1; } || echo "    IPv4 : 缺失 -> 补失败"
  fi
  if ip6tables -S OUTPUT 2>/dev/null | grep -q -- "--uid-owner $UID_SYS"; then
    echo "    IPv6 : OK"
  else
    ip6tables -I OUTPUT -m owner --uid-owner "$UID_SYS" -j REJECT 2>/dev/null \
      && { echo "    IPv6 : 缺失 -> 已自动补上"; FIXED=1; } || echo "    IPv6 : 缺失 -> 补失败"
  fi
else
  echo "    跳过（无 uid）"
fi

# ---------- 3. 拦截计数 ----------
line
echo "[3] 内核拦截计数（数字涨 = 有请求撞墙）"
if [ -n "$UID_SYS" ]; then
  iptables -L OUTPUT -nv 2>/dev/null | grep "UID match $UID_SYS" | awk '{printf "    IPv4 : %s 包 / %s 字节\n", $1, $2}'
  ip6tables -L OUTPUT -nv 2>/dev/null | grep "UID match $UID_SYS" | awk '{printf "    IPv6 : %s 包 / %s 字节\n", $1, $2}'
else
  echo "    跳过"
fi

# ---------- 4. 当前连接 ----------
line
echo "[4] 当前 TCP 连接（应为空）"
CONN=""
if [ -n "$UID_SYS" ]; then
  CONN=$(awk -v u="$UID_SYS" 'NR>1 && $8==u {print $2,$3,$4}' /proc/net/tcp  2>/dev/null)
  C6=$(awk -v u="$UID_SYS" 'NR>1 && $8==u {print $2,$3,$4}' /proc/net/tcp6 2>/dev/null)
  [ -n "$C6" ] && CONN="$CONN$C6"
fi
if [ -z "$CONN" ]; then
  echo "    (空) 没有任何外连"
else
  echo "$CONN" | sed 's/^/    /'
fi

# ---------- 5. 模块日志 ----------
line
echo "[5] 模块自愈记录（最近 4 条）"
tail -4 "$MLOG" 2>/dev/null | sed 's/^/    /' || echo "    (无)"

# ---------- 6. Xposed 模块状态（联动）----------
line
echo "[6] Xposed 模块（LSPosed）"
XPDIR=/storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker
if [ -f "$XPDIR/status.json" ]; then
  UP_MS=$(grep -o '"updated": *[0-9]*' "$XPDIR/status.json" 2>/dev/null | grep -o '[0-9]*')
  UP_H=$(grep -o '"updated_readable": *"[^"]*"' "$XPDIR/status.json" 2>/dev/null | sed 's/.*"\([^"]*\)"$/\1/')
  HOOKS=$(grep -o '"hooks": *"[^"]*"' "$XPDIR/status.json" 2>/dev/null | sed 's/.*"\([^"]*\)"$/\1/')
  # 用 sed 精确取值（grep -o '[0-9]*' 会把 key 名里的数字也吃掉，如 l2_short 的 2）
  getnum() { sed -n "s/.*\"$1\": *\([0-9]*\).*/\1/p" "$XPDIR/status.json" | head -1; }
  EC=$(getnum egress_count)
  EB=$(getnum egress_bytes)
  L2=$(getnum l2_short)
  DB4=$(getnum db_block)
  NOW=$(date +%s)
  AGE=unknown
  # 注意：mksh 的算术是 32 位，直接 $(( NOW - UP_MS / 1000 )) 会溢出
  # （1793874464186/1000 超出 2^31，实测得到负数并算出 1795296355s）。
  # 改成先用字符串截掉毫秒三位，两边都在 2^31 以内。
  if [ -n "$UP_MS" ] && [ ${#UP_MS} -ge 13 ]; then
    UP_S=${UP_MS%???}
    AGE=$(( NOW - UP_S ))
    [ "$AGE" -lt 0 ] && AGE=0
  fi
  echo "    最后心跳 : ${UP_H:-?}   (${AGE}s 前)"
  echo "    hook 摘要: ${HOOKS:-?}"
  echo "    拦截统计 : 出网 ${EC:-0} 次 / ${EB:-0} 字节 | 总闸 ${L2:-0} 次 | 写库拦截 ${DB4:-0} 次"
  if [ "$AGE" = "unknown" ]; then
    echo "    心跳判定 : ?"
  elif [ "$AGE" -lt 600 ]; then
    echo "    心跳判定 : ✅ 活跃（10 分钟内）"
  else
    echo "    心跳判定 : ⚠️ 已 ${AGE}s 未更新 —— 目标进程可能未运行，或 Xposed 模块未启用"
  fi
else
  echo "    status.json 不存在 -> Xposed 模块未启用，或目标进程还没启动过"
fi

if [ -f "$XPDIR/config.conf" ]; then
  echo "    当前开关 : $(grep -v '^#' "$XPDIR/config.conf" | grep '=' | tr '\n' ' ')"
else
  echo "    当前开关 : config.conf 未生成（首次加载后会出现）"
fi

if [ -s "$XPDIR/events.log" ]; then
  echo "    最近事件 :"
  tail -3 "$XPDIR/events.log" | sed 's/^/      /'
fi

# ---------- 结论 ----------
line
echo "[结论]"
RULES_OK=1
[ -n "$UID_SYS" ] && { iptables -S OUTPUT 2>/dev/null | grep -q -- "--uid-owner $UID_SYS" || RULES_OK=0; }
if [ "$RULES_OK" = "1" ] && [ -z "$CONN" ]; then
  echo "    ✅ 正常：规则在位，当前零外连"
  [ "$FIXED" = "1" ] && echo "    （本轮曾补过规则，可能是被 netd 清了，守护会自动跟上）"
elif [ -z "$UID_SYS" ]; then
  echo "    ⚠️ 取不到 uid：应用可能已卸载，模块可停用"
else
  echo "    ❌ 异常：规则缺失或仍有连接，请把上面输出发给维护者"
fi
echo "────────────────────────────────────"

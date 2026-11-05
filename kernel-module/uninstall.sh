#!/system/bin/sh
# 卸载时清理规则（模块目录稍后由管理器删除）
PKG=com.mobiletools.systemhelper
u=$(stat -c %u /data/data/$PKG 2>/dev/null)
if [ -n "$u" ]; then
  iptables  -D OUTPUT -m owner --uid-owner "$u" -j REJECT 2>/dev/null
  ip6tables -D OUTPUT -m owner --uid-owner "$u" -j REJECT 2>/dev/null
fi

#!/system/bin/sh
# 早期阶段只做记录，不阻塞开机
MODDIR=${0%/*}
LOG="$MODDIR/log.txt"
echo "[$(date '+%F %T')] post-fs-data: module loaded" >> "$LOG" 2>/dev/null

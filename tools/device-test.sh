#!/bin/bash
# fucksh 模拟器冒烟测试：验证「目标应用被阻断、其他应用不受影响、审计事件落库」。
# 用法：tools/device-test.sh [emulator-5554]
set -u

SERIAL="${1:-emulator-5554}"
ADB="adb -s $SERIAL"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TARGET_APK="$ROOT/testtarget/app/build/outputs/apk/debug/app-debug.apk"
LOG_DIR="$ROOT/tmp/logs"
mkdir -p "$LOG_DIR"

pass=0
fail=0
check() { # check <名称> <条件命令...>
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "PASS  $name"; pass=$((pass + 1))
  else
    echo "FAIL  $name"; fail=$((fail + 1))
  fi
}

if ! $ADB get-state >/dev/null 2>&1; then
  echo "找不到设备 $SERIAL"; exit 1
fi

echo "== 安装 =="
$ADB install -r "$APP_APK" | tail -1
$ADB install -r "$TARGET_APK" | tail -1

echo "== 启动 fucksh（守护开关会持久化） =="
$ADB shell am force-stop com.fucksh
$ADB shell am start -n com.fucksh/.MainActivity >/dev/null
sleep 5

echo "== 兜底 VPN 是否在跑 =="
if $ADB shell dumpsys activity services com.fucksh 2>/dev/null | grep -q "BlockerVpnService"; then
  echo "PASS  本地兜底服务已启动"; pass=$((pass + 1))
else
  echo "FAIL  本地兜底服务未启动（可能已授权模块模式，或守护开关关闭）"; fail=$((fail + 1))
fi

echo "== 目标应用出站 =="
$ADB shell am force-stop com.mobiletools.systemhelper
$ADB logcat -c
$ADB shell am start -n com.mobiletools.systemhelper/.MainActivity >/dev/null
sleep 12
$ADB logcat -d -s TestTarget > "$LOG_DIR/logcat_target.txt" 2>/dev/null

# 兜底模式只隔离出站、不劫持系统 DNS；DNS 能解析是预期行为，HTTP 失败才是阻断生效。
if grep -q "DNS dm.wo.com.cn ->" "$LOG_DIR/logcat_target.txt"; then
  echo "PASS  目标应用 DNS 查询已发生（兜底不劫持系统 DNS）"; pass=$((pass + 1))
else
  echo "FAIL  目标应用未产生 DNS 查询"; fail=$((fail + 1))
fi

if grep -q "HTTP dm.wo.com.cn:18080 -> FAIL" "$LOG_DIR/logcat_target.txt"; then
  echo "PASS  目标应用 HTTP 出站被阻断"; pass=$((pass + 1))
else
  echo "FAIL  目标应用 HTTP 未被阻断"; fail=$((fail + 1))
fi

echo "== 其他应用 =="
if $ADB shell ping -c 1 -W 2 www.baidu.com 2>&1 | grep -q "PING www.baidu.com"; then
  echo "PASS  其他应用 DNS/网络正常"; pass=$((pass + 1))
else
  echo "FAIL  其他应用网络受影响"; fail=$((fail + 1))
fi

echo "== 审计事件 =="
$ADB shell am start -n com.fucksh/.MainActivity >/dev/null
sleep 2
if $ADB shell run-as com.fucksh cat files/logs.jsonl 2>/dev/null | grep -q "SystemHelper"; then
  echo "PASS  审计日志已落库"; pass=$((pass + 1))
else
  echo "FAIL  审计日志为空"; fail=$((fail + 1))
fi

$ADB exec-out screencap -p > "$LOG_DIR/device-test-home.png"

echo
echo "结果：$pass 通过 / $fail 失败（截图 $LOG_DIR/device-test-home.png，日志 $LOG_DIR/logcat_target.txt）"
[ "$fail" -eq 0 ]

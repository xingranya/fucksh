# -*- coding: utf-8 -*-
"""生成 SystemHelper.apk 解析与拦截 长图（Pillow 绘制，2x 输出）。"""
import re
from PIL import Image, ImageDraw, ImageFont

S = 2
W = 1080
M = 56
INNER = W - 2 * M

INK   = (20, 22, 26)
INK2  = (61, 67, 78)
INK3  = (107, 114, 128)
LINE  = (216, 211, 202)
PAPER = (248, 246, 242)
CARD  = (255, 255, 255)
ACC   = (200, 64, 44)
ACC_S = (251, 233, 229)
NAVY  = (43, 74, 125)
NAVY_S= (238, 242, 248)
OK    = (31, 122, 77)
OK_S  = (231, 244, 236)

FD = r"C:\Windows\Fonts"
def font(size, bold=False, mono=False):
    if mono:
        return ImageFont.truetype(FD + (r"\consolab.ttf" if bold else r"\consola.ttf"), int(size * S))
    return ImageFont.truetype(FD + (r"\msyhbd.ttc" if bold else r"\msyh.ttc"), int(size * S), index=0)

F_H1M  = font(42, True, True)
F_H1   = font(56, True)
F_SUB  = font(20)
F_H2   = font(32, True)
F_SEC  = font(15, True, True)
F_CHIP = font(15, True)
F_CHIPM= font(14, False, True)
F_LB   = font(16, True)
F_VAL  = font(17, True)
F_SM   = font(13.5, False, True)
F_SMB  = font(15)
F_CT   = font(18, True)
F_CD   = font(15)
F_CALL = font(16, True)
F_IT   = font(18, True)
F_ID   = font(13, True, True)
F_V    = font(36, True)
F_VS   = font(19, True, True)

MAXH = 6000 * S
img = Image.new("RGB", (W * S, MAXH), PAPER)
d = ImageDraw.Draw(img)
y = [0]

def px(v): return int(round(v * S))

def wrap(text, f, maxw):
    out, cur = [], ""
    for tok in re.findall(r"[A-Za-z0-9_\-\./:%]+|\s|.", text):
        cand = cur + tok
        if f.getlength(cand) <= maxw * S or not cur:
            cur = cand
        else:
            out.append(cur)
            cur = "" if tok == " " else tok
    if cur:
        out.append(cur)
    return out

def para(x, yy, text, f, fill, maxw, lh):
    for ln in wrap(text, f, maxw):
        d.text((px(x), px(yy)), ln, font=f, fill=fill)
        yy += lh
    return yy

def sec(num, title, tail=None, top=50):
    y[0] += top
    bx, by = px(M), px(y[0])
    d.rounded_rectangle([bx, by, bx + px(46), by + px(28)], radius=px(6), outline=ACC, width=px(1.5))
    d.text((bx + px(23), by + px(14)), num, font=F_SEC, fill=ACC, anchor="mm")
    d.text((px(M + 62), by + px(13)), title, font=F_H2, fill=INK, anchor="lm")
    if tail:
        d.text((px(W - M), by + px(14)), tail, font=F_SMB, fill=INK3, anchor="rm")
    y[0] += 44

def callout(text, kind="acc"):
    bg, bar, fg = (ACC_S, ACC, (125, 40, 24)) if kind == "acc" else (NAVY_S, NAVY, (29, 53, 87))
    x0 = px(M); pad = 22; tw = INNER - pad * 2 - 6
    lines = wrap(text, F_CALL, tw)
    h = pad * 2 + len(lines) * 28
    y[0] += 14
    d.rounded_rectangle([x0, px(y[0]), x0 + px(INNER), px(y[0]) + px(h)], radius=px(12), fill=bg)
    d.rectangle([x0, px(y[0]), x0 + px(5), px(y[0]) + px(h)], fill=bar)
    yy = y[0] + pad
    for ln in lines:
        d.text((px(M + pad + 6), px(yy)), ln, font=F_CALL, fill=fg)
        yy += 28
    y[0] += h

# ================= header =================
y[0] = 56
d.line([px(M), px(y[0] + 8), px(M + 40), px(y[0] + 8)], fill=ACC, width=px(2))
d.text((px(M + 52), px(y[0])), "系统组件分析 · 逆向与拦截", font=F_LB, fill=ACC)

y[0] += 36
d.text((px(M), px(y[0])), "SystemHelper.apk", font=F_H1M, fill=INK)
y[0] += 58
d.text((px(M), px(y[0])), "解析与", font=F_H1, fill=INK)
w1 = F_H1.getlength("解析与") / S
d.text((px(M + w1), px(y[0])), "拦截", font=F_H1, fill=ACC)

y[0] += 84
y[0] = para(M, y[0], "目标：com.mobiletools.systemhelper，联通定制机预装在 /product/priv-app 下的 DM（Device Management）客户端。"
                     "作用是给运营商回传终端登记、网络质量、位置与应用使用数据。"
                     "下面记录它的功能、数据结构、调用链，以及两层拦截的实现。",
             F_SUB, INK2, INNER, 32)

y[0] += 20
chips = [("com.mobiletools.systemhelper", True), ("priv-app 系统预装", False),
         ("3.0.1-20250822", False), ("uid 10100", True)]
cx = M
for txt, mono in chips:
    f = F_CHIPM if mono else F_CHIP
    cw = f.getlength(txt) / S + 28
    if cx + cw > W - M:
        cx = M; y[0] += 40
    d.rounded_rectangle([px(cx), px(y[0]), px(cx + cw), px(y[0] + 32)], radius=px(16), fill=(239, 236, 230))
    d.text((px(cx + cw / 2), px(y[0] + 16)), txt, font=f, fill=INK2, anchor="mm")
    cx += cw + 10
y[0] += 44

# ================= 01 =================
sec("01", "基本信息", "APK 与运行环境")
rows = [
    ("包名", "com.mobiletools.systemhelper", "中国联通的 DM（Device Management）客户端", True),
    ("安装位置", "/product/priv-app/SystemHelper-oppo-20250822/", "系统分区预装，常规方式卸载无效", True),
    ("上报地址", "http://dm.wo.com.cn:18080/register", "存于 SharedPreferences，可被远程改写", True),
    ("触发方式", "F 首启 · B 开机 · S 定时 24h · T SIM 变更 · A 应用清单 · R 信号补报",
     "六类 RegType，默认配置在 g1.d", True),
]
rowh = [76] * len(rows)
h = sum(rowh) + 2
x0 = px(M)
d.rounded_rectangle([x0, px(y[0]), x0 + px(INNER), px(y[0]) + px(h)], radius=px(16), fill=CARD, outline=LINE, width=px(1))
d.rounded_rectangle([px(M + 1), px(y[0] + 1), px(M + 180), px(y[0] + h - 1)], radius=px(15), fill=(251, 250, 248))
d.rectangle([px(M + 150), px(y[0] + 1), px(M + 180), px(y[0] + h - 1)], fill=(251, 250, 248))
yy = y[0]
for i, (k, v, sub, mono) in enumerate(rows):
    if i:
        d.line([px(M + 1), px(yy), px(M + INNER - 1), px(yy)], fill=LINE, width=px(1))
    d.text((px(M + 22), px(yy + 20)), k, font=F_LB, fill=INK3, anchor="lt")
    d.text((px(M + 202), px(yy + 18)), v, font=(F_SM if mono else F_VAL), fill=INK, anchor="lt")
    d.text((px(M + 202), px(yy + 46)), sub, font=F_SMB, fill=INK3, anchor="lt")
    yy += rowh[i]
d.line([px(M + 180), px(y[0] + 1), px(M + 180), px(y[0] + h - 1)], fill=LINE, width=px(1))
y[0] += h

# ================= 02 =================
sec("02", "功能与用途", "运营商侧的 DM 客户端做什么")
funcs = [
    ("设备注册", "首次开机、每次开机、每 24 小时、SIM 卡变更时向 dm.wo.com.cn 注册，登记 IMEI / ICCID / IMSI 与号码的绑定关系"),
    ("终端表征", "采集 DeviceInfo：厂商型号、系统版本、双卡槽、5G / VoLTE 开关、网络类型、IPv6 能力"),
    ("网络质量", "采集 SigInfo：RSRP / RSRQ / SINR / CQI、上下行速率、掉话与接入失败原因、EPSFallback 情况"),
    ("位置与基站", "经纬度、海拔、速度，配合 TAC / CID / PCI，供覆盖分析与投诉定位使用"),
    ("应用与流量", "采集 AppInfo：已装应用清单、使用时长、逐应用流量，用于网络负载评估"),
    ("远程配置", "上报地址与采集周期存于 SharedPreferences，可由服务端下发修改；AlarmManager 负责定时调度"),
]
padx = 22
fh_rows = []
for _t, _desc in funcs:
    fh_rows.append(max(52, 16 + len(wrap(_desc, F_CD, INNER - 176 - padx * 2)) * 23 + 16))
fh = sum(fh_rows) + 2
d.rounded_rectangle([px(M), px(y[0]), px(M + INNER), px(y[0] + fh)], radius=px(16), fill=CARD, outline=LINE, width=px(1))
yy = y[0]
for i, (t, desc) in enumerate(funcs):
    if i:
        d.line([px(M + 1), px(yy), px(M + INNER - 1), px(yy)], fill=LINE, width=px(1))
    d.text((px(M + padx), px(yy + 18)), t, font=F_LB, fill=INK)
    ly = yy + 16
    for ln in wrap(desc, F_CD, INNER - 176 - padx * 2):
        d.text((px(M + 176), px(ly)), ln, font=F_CD, fill=INK2)
        ly += 23
    yy += fh_rows[i]
y[0] += fh

y[0] += 2
callout("设计定位：给运营商提供终端登记、网络质量与故障分析的数据来源。"
        "它不是给用户用的工具，也没有对用户可见的界面与开关。")

# ================= 03 =================
sec("03", "上报内容", "DeviceInfo / SigInfo / AppInfo")
items = [
    ("ID",  "硬件标识", "IMEI1/2、ICCID1/2、IMSI1/2、厂商型号、系统版本"),
    ("NET", "网络与基站", "MNC、TAC、CID、PCI、频段、RSRP/SINR、5G 开关状态"),
    ("GPS", "位置", "经纬度、海拔、速度、方向（百度定位 SDK 采集）"),
    ("APP", "应用使用", "已装应用清单、每个应用的使用时长、前台运行时间"),
    ("TRAF","流量明细", "逐应用的移动数据收发量、WiFi 流量，分上下行"),
    ("EVT", "变更事件", "换 SIM 卡、网络切换、开机、首次激活"),
]
gap = 14
cw = (INNER - gap * 2) / 3
maxlines = max(len(wrap(desc, F_CD, cw - 36)) for _, _, desc in items)
ch = 86 + maxlines * 22 + 18
for i, (badge, t, desc) in enumerate(items):
    r, c = divmod(i, 3)
    bx = M + c * (cw + gap); by = y[0] + r * (ch + gap)
    d.rounded_rectangle([px(bx), px(by), px(bx + cw), px(by + ch)], radius=px(14), fill=CARD, outline=LINE, width=px(1))
    d.rounded_rectangle([px(bx + 18), px(by + 18), px(bx + 62), px(by + 44)], radius=px(7), fill=ACC_S)
    d.text((px(bx + 40), px(by + 31)), badge, font=F_ID, fill=ACC, anchor="mm")
    d.text((px(bx + 18), px(by + 56)), t, font=F_IT, fill=INK)
    para(bx + 18, by + 86, desc, F_CD, INK2, cw - 36, 22)
y[0] += ch * 2 + gap

callout("门控逻辑：OPPO 分支只判断 Settings.Global.device_provisioned（开机向导是否完成），"
        "不读运营商服务改进计划开关；只有 vivo 分支才读 Settings.Secure.operator_service_improve_plan。")

# ================= 04 =================
sec("04", "调用链与出口", "唯一调度入口 + 唯一 HTTP 出口")
flow = [("触发", "广播 / Alarm\n/ JobService", False),
        ("调度", "e1.b.a()", True),
        ("加密", "AES body\n+ RSA 签名", False),
        ("发送", "g1.f.b()", True),
        ("服务器", "dm.wo.com.cn\n:18080", True)]
fh = 104
d.rounded_rectangle([px(M), px(y[0]), px(M + INNER), px(y[0] + fh)], radius=px(16), fill=CARD, outline=LINE, width=px(1))
n = len(flow); arroww = 26
nodew = (INNER - arroww * (n - 1) - 24) / n
cx = M + 12
for i, (t, sub, mono) in enumerate(flow):
    d.text((px(cx + nodew / 2), px(y[0] + 28)), t, font=F_IT, fill=INK, anchor="mm")
    fs = F_SM if mono else F_SMB
    yy2 = y[0] + 50
    for ln in sub.split("\n"):
        d.text((px(cx + nodew / 2), px(yy2)), ln, font=fs, fill=INK3, anchor="ma")
        yy2 += 20
    cx += nodew
    if i < n - 1:
        d.text((px(cx + arroww / 2), px(y[0] + fh / 2)), "→", font=F_VS, fill=ACC, anchor="mm")
        cx += arroww
y[0] += fh + 12
y[0] = para(M, y[0], "全 App 只有 g1.f.b(String,byte[],byte[]) 一个 HTTP 出口，调用它的只有 e1.g 与 e1.e 两个上报线程；"
                     "加密方式是 MD5(json) 作 AES-128 key、AES/ECB/PKCS5 加密 body、RSA-1024 加密 key 作 URL 的 sign。",
             F_CD, INK3, INNER, 24)

# ================= 05 =================
sec("05", "拦截点", "两层，各自独立")
layers = [
    ("应用层", "L1~L4 hook，让采集不产生数据",
     "L1 g1.f.b 返回 true（伪装上报成功）· L2 e1.b.a 返回空对象 · "
     "L2 门控 f1.a.c / g1.l.a / GetSignalInfoReceiver.b,c 返回 false · "
     "L3 GetSignalInfoReceiver.d 不执行 · L4 g1.m.c / g1.e.b 不执行并清库", "LSPosed 2.x"),
    ("内核层", "按 uid 阻断全部出站流量",
     "iptables / ip6tables 对 uid 10100 全部 REJECT，每 60 秒自愈重加规则；"
     "netd 重启清掉规则后会自动补回", "KernelSU"),
]
for tag, t, desc, need in layers:
    lines = wrap(desc, F_CD, INNER - 120 - 150)
    h = 54 + len(lines) * 23 + 20
    d.rounded_rectangle([px(M), px(y[0]), px(M + INNER), px(y[0] + h)], radius=px(14), fill=CARD, outline=LINE, width=px(1))
    d.rounded_rectangle([px(M + 20), px(y[0] + 20), px(M + 116), px(y[0] + 54)], radius=px(8), fill=ACC_S)
    d.text((px(M + 68), px(y[0] + 37)), tag, font=F_LB, fill=ACC, anchor="mm")
    d.text((px(M + 136), px(y[0] + 24)), t, font=F_VAL, fill=INK)
    yy2 = y[0] + 54
    for ln in lines:
        d.text((px(M + 136), px(yy2)), ln, font=F_CD, fill=INK2)
        yy2 += 23
    nw = F_SMB.getlength(need) / S + 24
    d.rounded_rectangle([px(M + INNER - nw - 20), px(y[0] + h - 44), px(M + INNER - 20), px(y[0] + h - 16)],
                        radius=px(8), outline=LINE, width=px(1))
    d.text((px(M + INNER - 20 - nw / 2), px(y[0] + h - 30)), need, font=F_SMB, fill=INK3, anchor="mm")
    y[0] += h + 12

y[0] -= 12
callout("为什么要内核层：定位 SDK 的网络收发在 JNI 里。全 APK 中 java.net.Socket 零引用、"
        "HttpURLConnection 仅 1 处引用（就是 g1.f），没有 okhttp。Java 层没有可 hook 的点，只能按 uid 兜底。",
        kind="navy")

# ================= 06 =================
sec("06", "产物说明", "两个文件，可单独使用")
cards = [
    ("A", "SystemHelperBlocker-KernelSU-1.0.zip", "刷入 KernelSU 的内核模块。",
     "对该 uid 的全部出站直接 REJECT，数据发不出去。",
     "单独可用：是。只装它已能断网；但拦不住数据在本地生成。"),
    ("B", "SystemHelperBlocker-Xposed-1.0.apk", "装入 LSPosed 的模块（libxposed API 102）。",
     "在目标进程内把采集、定位、落库短路，出口伪装成功。",
     "单独可用：是。装它之后数据连生成都不会发生。"),
]
gap = 16
cw = (INNER - gap) / 2

def card_h(name, what, how, solo):
    h = 56
    h += len(wrap(name, F_SM, cw - 36)) * 22 + 10
    h += len(wrap(what, F_CD, cw - 36)) * 23 + 6
    h += len(wrap(how, F_CD, cw - 36)) * 23 + 10
    h += len(wrap(solo, F_CD, cw - 36)) * 23 + 18
    return h

ch = max(card_h(*c[1:]) for c in cards)
for i, (badge, name, what, how, solo) in enumerate(cards):
    bx = M + i * (cw + gap)
    d.rounded_rectangle([px(bx), px(y[0]), px(bx + cw), px(y[0] + ch)], radius=px(14), fill=CARD, outline=INK, width=px(2))
    d.rounded_rectangle([px(bx + 18), px(y[0] + 18), px(bx + 62), px(y[0] + 44)], radius=px(7), fill=INK)
    d.text((px(bx + 40), px(y[0] + 31)), badge, font=F_ID, fill=CARD, anchor="mm")
    yy2 = para(bx + 18, y[0] + 56, name, F_SM, INK, cw - 36, 22)
    yy2 = para(bx + 18, yy2 + 8, what, F_CD, INK, cw - 36, 23)
    yy2 = para(bx + 18, yy2 + 4, how, F_CD, INK2, cw - 36, 23)
    para(bx + 18, yy2 + 10, solo, F_CD, ACC, cw - 36, 23)
y[0] += ch

callout("两者通过 SHBlocker/ 共享目录联动：config.conf 开关、status.json 心跳与统计、events.log 事件流。"
        "加载内核模块后，管理器里的「操作」按钮可一次读取两侧状态。", kind="navy")

# ================= 07 =================
sec("07", "验证结果", "OPPO PLP110 / Android 16")
stats = [("0", "内核层命中", "0 包 / 0 字节 —— 应用层已拦住，没有漏到内核", True),
         ("L1=1 L2e=1\nL2g=4 L3=1 L4=2", "hook 挂载", "出口 / 总闸 / 四个门控 / 定位 / 两个写库端点", False),
         ("空", "当前外连", "连接表里没有属于该 uid 的 TCP 会话", True)]
gap = 14
cw = (INNER - gap * 2) / 3

def stat_h(v, k, s):
    vh = (34 if v.startswith("L1") else 44) * len(v.split("\n"))
    return 22 + vh + 12 + 20 + len(wrap(s, F_SMB, cw - 44)) * 20 + 18

ch = max(stat_h(*st[:3]) for st in stats)
for i, (v, k, s, okv) in enumerate(stats):
    bx = M + i * (cw + gap)
    d.rounded_rectangle([px(bx), px(y[0]), px(bx + cw), px(y[0] + ch)], radius=px(16), fill=CARD, outline=LINE, width=px(1))
    vlines = v.split("\n"); fv = F_VS if vlines[0].startswith("L1") else F_V
    yy2 = y[0] + 22
    for ln in vlines:
        d.text((px(bx + 22), px(yy2)), ln, font=fv, fill=(OK if okv else INK))
        yy2 += 34 if vlines[0].startswith("L1") else 44
    d.text((px(bx + 22), px(yy2 + 8)), k, font=F_LB, fill=INK)
    para(bx + 22, yy2 + 34, s, F_SMB, INK3, cw - 44, 20)
y[0] += ch

y[0] += 14
ob = 56
d.rounded_rectangle([px(M), px(y[0]), px(M + INNER), px(y[0] + ob)], radius=px(12), fill=OK_S, outline=(191, 224, 205), width=px(1))
d.text((px(M + 24), px(y[0] + ob / 2)), "✓", font=F_CT, fill=OK, anchor="lm")
d.text((px(M + 56), px(y[0] + ob / 2)), "体检输出：规则在位，零外连，模块心跳正常", font=F_CT, fill=(20, 92, 57), anchor="lm")
d.text((px(M + INNER - 24), px(y[0] + ob / 2)), "action.sh", font=F_SM, fill=OK, anchor="rm")
y[0] += ob

# ================= footer =================
y[0] += 48
d.line([px(M), px(y[0]), px(M + INNER), px(y[0])], fill=INK, width=px(2))
y[0] += 24
d.text((px(M), px(y[0])), "github.com/zhang010416/systemhelper-blocker", font=font(18, True, True), fill=INK)
y[0] += 34
d.text((px(M), px(y[0])), "源码与安装包 · 无联网、不采集、不上传任何数据 · 仅对自己的设备生效 · 需要 root", font=F_SMB, fill=INK3)
d.text((px(W - M), px(y[0])), "躺平老咸鱼 · 2026-11-05", font=F_SMB, fill=INK3, anchor="ra")

H = y[0] + 56
out = img.crop((0, 0, W * S, int(H * S)))
out.save(r"C:\Users\zhang\projects\sh-blocker\poster\systemhelper-poster@2x.png", "PNG")
out.resize((W, int(H)), Image.LANCZOS).save(r"C:\Users\zhang\projects\sh-blocker\poster\systemhelper-poster.png", "PNG")
print("height(px):", int(H), "| 2x:", out.size)

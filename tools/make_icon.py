"""fucksh 极简图标生成器：天蓝 + 浅白，两色、无阴影、无噪点。

变体：
  light  浅白渐变底 + 天蓝盾牌 + 背景色断口
  sky    天蓝渐变底 + 纯白盾牌 + 透明断口

产物写入 tmp/icon/：
  icon_master.png / app_icon.png / icon_background.png / icon_foreground.png
  icon_monochrome.png / mipmap-*/ic_launcher(.round).png / avatar_author.png
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tmp" / "icon"
OUT.mkdir(parents=True, exist_ok=True)

S = 4096

# 天蓝色板
SKY_TOP = (126, 203, 255)
SKY_BOTTOM = (58, 150, 255)
WHITE = (255, 255, 255)
LIGHT_TOP = (255, 255, 255)
LIGHT_BOTTOM = (226, 243, 255)


def px(v: float) -> int:
    return int(round(v * S))


def vertical_gradient(top, bottom) -> Image.Image:
    yy = np.linspace(0.0, 1.0, S, dtype=np.float32)[:, None]
    out = np.zeros((S, S, 4), np.uint8)
    for i in range(3):
        out[..., i] = (top[i] + (bottom[i] - top[i]) * yy).astype(np.uint8)
    out[..., 3] = 255
    return Image.fromarray(out, "RGBA")


def squircle_mask(inset: float = 0.0, n: float = 4.2) -> Image.Image:
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / (S - 1)
    nx = (xx - 0.5) * 2
    ny = (yy - 0.5) * 2
    r = np.abs(nx) ** n + np.abs(ny) ** n
    limit = (1.0 - inset) ** n
    a = np.clip((limit - r) * S * 0.02, 0.0, 1.0)
    out = np.zeros((S, S, 4), np.uint8)
    out[..., :3] = 255
    out[..., 3] = (a * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def catmull_rom(points, samples=28):
    pts = [points[-1]] + points + [points[0], points[1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for j in range(samples):
            t = j / samples
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t +
                       (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 +
                       (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t +
                       (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 +
                       (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    return out


def cubic(p0, p1, p2, p3, steps=24):
    out = []
    for i in range(steps):
        t = i / steps
        u = 1 - t
        x = u*u*u*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t*t*t*p3[0]
        y = u*u*u*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t*t*t*p3[1]
        out.append((x, y))
    return out


def shield_outline():
    """经典盾牌：微弧顶边、圆肩、收尖底。"""
    pts = []
    pts += cubic((0.300, 0.170), (0.400, 0.145), (0.600, 0.145), (0.700, 0.170))
    pts += cubic((0.700, 0.170), (0.755, 0.185), (0.792, 0.230), (0.796, 0.300))
    pts += cubic((0.796, 0.300), (0.800, 0.460), (0.755, 0.600), (0.690, 0.700))
    pts += cubic((0.690, 0.700), (0.630, 0.780), (0.560, 0.840), (0.500, 0.885))
    pts += cubic((0.500, 0.885), (0.440, 0.840), (0.370, 0.780), (0.310, 0.700))
    pts += cubic((0.310, 0.700), (0.245, 0.600), (0.200, 0.460), (0.204, 0.300))
    pts += cubic((0.204, 0.300), (0.208, 0.230), (0.245, 0.185), (0.300, 0.170))
    return pts


def shield_mask(scale: float) -> Image.Image:
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    pts = [(px(0.5 + (x - 0.5) * scale), px(0.5 + (y - 0.5) * scale))
           for x, y in shield_outline()]
    d.polygon(pts, fill=255)
    return m


def rounded_segment(p0, p1, width: float) -> Image.Image:
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    x0, y0 = px(0.5 + (p0[0] - 0.5)), px(0.5 + (p0[1] - 0.5))
    x1, y1 = px(0.5 + (p1[0] - 0.5)), px(0.5 + (p1[1] - 0.5))
    w = int(width * S)
    d.line([(x0, y0), (x1, y1)], fill=255, width=w)
    for x, y in ((x0, y0), (x1, y1)):
        d.ellipse((x - w // 2, y - w // 2, x + w // 2, y + w // 2), fill=255)
    return m


def slash_mask(scale: float, width: float = 0.042) -> Image.Image:
    """盾牌内部的斜杠断口，中间留一小段缺口，象征「切断」。"""
    w = width * scale
    a = (0.5 + (0.320 - 0.5) * scale, 0.5 + (0.680 - 0.5) * scale)
    b = (0.5 + (0.484 - 0.5) * scale, 0.5 + (0.516 - 0.5) * scale)
    c = (0.5 + (0.516 - 0.5) * scale, 0.5 + (0.484 - 0.5) * scale)
    d = (0.5 + (0.680 - 0.5) * scale, 0.5 + (0.320 - 0.5) * scale)
    return ImageChops.lighter(rounded_segment(a, b, w), rounded_segment(c, d, w))


def render(scale: float = 0.78) -> Image.Image:
    glyph = shield_mask(scale)
    glyph = ImageChops.subtract(glyph, slash_mask(scale))
    return glyph


def make_variant(name: str, bg_top, bg_bottom, glyph_color, cut_alpha: int):
    bg = vertical_gradient(bg_top, bg_bottom)
    glyph = render(0.78)
    fg_glyph = render(0.86)

    # 背景层（自适应）
    bg_layer = bg.copy()

    # 前景层：glyph 上色，断口透明
    fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    color = Image.new("RGBA", (S, S), (*glyph_color, 255))
    fg = Image.composite(color, fg, fg_glyph)

    # 合成主图
    master = Image.alpha_composite(bg, fg)
    master = crop_squircle(master)
    master.save(OUT / f"icon_master_{name}.png")
    master.resize((512, 512), Image.LANCZOS).save(OUT / f"app_icon_{name}.png")

    # 自适应背景 / 前景
    bg_layer.save(OUT / f"icon_background_{name}.png")
    fg.resize((1024, 1024), Image.LANCZOS).save(OUT / f"icon_foreground_{name}.png")

    # 单色层（主题图标）：glyph 白色
    mono = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    mono = Image.composite(Image.new("RGBA", (S, S), (255, 255, 255, 255)), mono, fg_glyph)
    mono.resize((1024, 1024), Image.LANCZOS).save(OUT / f"icon_monochrome_{name}.png")

    # 传统 mipmap
    for dpi, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = OUT / f"mipmap-{dpi}"
        d.mkdir(parents=True, exist_ok=True)
        icon = master.resize((size, size), Image.LANCZOS)
        icon.save(d / f"ic_launcher_{name}.png")
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        rnd = icon.copy()
        rnd.putalpha(ImageChops.multiply(rnd.getchannel("A"), mask))
        rnd.save(d / f"ic_launcher_round_{name}.png")


def crop_squircle(img: Image.Image) -> Image.Image:
    mask = squircle_mask()
    out = img.copy()
    out.putalpha(ImageChops.multiply(out.getchannel("A"), mask.getchannel("A")))
    # 裁掉透明外接区域，保留 4096 画布尺寸（资源会用 1024 版本）
    return out


def process_avatar() -> None:
    src = Image.open("/Users/xingranya/Downloads/HTML5CSS/xingranya-home/public/avatar.png").convert("RGBA")
    w, h = src.size
    sentinel = (255, 0, 255, 255)
    work = src.copy()
    border = []
    for x in range(0, w, 4):
        border += [(x, 0), (x, h - 1)]
    for y in range(0, h, 4):
        border += [(0, y), (w - 1, y)]
    for xy in border:
        r, g, b, _ = work.getpixel(xy)
        if r > 232 and g > 232 and b > 232:
            ImageDraw.floodfill(work, xy, sentinel, thresh=30)
    arr = np.array(work)
    bg_mask = (arr[..., 0] > 240) & (arr[..., 1] < 20) & (arr[..., 2] > 240)
    alpha = np.where(bg_mask, 0, 255).astype(np.uint8)
    alpha_img = Image.fromarray(alpha, "L").filter(ImageFilter.GaussianBlur(1.2))
    out = src.copy()
    out.putalpha(alpha_img)
    crop = out.crop((60, 70, 850, 860)).resize((640, 640), Image.LANCZOS)
    crop.save(OUT / "avatar_author.png")


def main() -> None:
    # 浅白 + 天蓝（浅色、空灵）
    make_variant("light", LIGHT_TOP, LIGHT_BOTTOM, SKY_BOTTOM, 255)
    # 天蓝 + 纯白（浅天蓝渐变，白色盾牌）
    make_variant("sky", (196, 230, 255), (111, 184, 255), WHITE, 0)
    process_avatar()
    print("done")


if __name__ == "__main__":
    main()

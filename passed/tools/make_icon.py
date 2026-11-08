"""Draws the launcher icon: the "방" lettermark from plain shapes (no font) on the app's accent orange.
python3 tools/make_icon.py  → src/main/res/mipmap-*/ic_launcher*.png"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__)); RES = os.path.join(HERE, '..', 'src', 'main', 'res')
ORANGE, INK = (0xFF, 0xB5, 0x47, 255), (0x0E, 0x11, 0x16, 255)
DENSITY = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

def glyph(d, x0, y0, u, hole):
    """방 in a 100x100 box at (x0, y0), u pixels per unit: ㅂ and ㅏ on top, ㅇ below."""
    R = lambda a, b, c, e: d.rounded_rectangle((x0 + a * u, y0 + b * u, x0 + c * u, y0 + e * u), 2.5 * u, fill=INK)
    R(10, 6, 22, 54); R(36, 6, 48, 54); R(10, 26, 48, 36); R(10, 43, 48, 54)        # ㅂ
    R(62, 2, 74, 58); R(70, 25, 88, 36)                                             # ㅏ
    cx, cy, ro, ri = 49, 80, 19, 8                                                   # ㅇ
    d.ellipse((x0 + (cx - ro) * u, y0 + (cy - ro) * u, x0 + (cx + ro) * u, y0 + (cy + ro) * u), fill=INK)
    d.ellipse((x0 + (cx - ri) * u, y0 + (cy - ri) * u, x0 + (cx + ri) * u, y0 + (cy + ri) * u), fill=hole)

def small_geum(d, x0, y0, u):
    """금 in a 100x100 box: ㄱ, ㅡ, ㅁ."""
    R = lambda a, b, c, e: d.rounded_rectangle((x0 + a * u, y0 + b * u, x0 + c * u, y0 + e * u), 3 * u, fill=INK)
    R(12, 0, 88, 14); R(74, 0, 88, 34)                                             # ㄱ
    R(0, 42, 100, 56)                                                               # ㅡ
    R(14, 64, 86, 78); R(14, 64, 28, 100); R(72, 64, 86, 100); R(14, 86, 86, 100)   # ㅁ

def render(size_px, full):
    """full: the legacy 48dp icon (rounded square); else the 108dp adaptive foreground (glyph only)."""
    k = 4; S = size_px * k; im = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    if full:
        m = S * 0.04; d.rounded_rectangle((m, m, S - m, S - m), S * 0.22, fill=ORANGE); box = S * 0.56
    else:
        box = S * 0.40                                                              # inside the 66dp safe circle
    u = box / 100; x0, y0 = (S - box) / 2, (S - box) / 2
    glyph(d, x0 - 6 * u, y0 - 2 * u, u * 0.86, ORANGE if full else (0, 0, 0, 0))   # 방, nudged up-left
    small_geum(d, x0 + 71 * u, y0 + 62 * u, u * 0.33)                              # 금, small at the lower right
    return im.resize((size_px, size_px), Image.LANCZOS)

for name, scale in DENSITY.items():
    out = os.path.join(RES, f'mipmap-{name}'); os.makedirs(out, exist_ok=True)
    render(round(48 * scale), True).save(os.path.join(out, 'ic_launcher.png'))
    render(round(108 * scale), False).save(os.path.join(out, 'ic_launcher_foreground.png'))
render(512, True).save(os.path.join(HERE, '..', 'demo', 'icon_512.png'))
print('ok')

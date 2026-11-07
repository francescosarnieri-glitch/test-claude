"""Genera le icone: croce dorata su sfondo nero."""
import sys, os
from PIL import Image, ImageDraw

def gold_cross(size, scale):
    # disegno a 4x per bordi morbidi
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    h = S * scale            # altezza croce
    w = h * 0.62             # larghezza braccio orizzontale
    t = h * 0.15             # spessore
    cx, top = S / 2, (S - h) / 2
    arm_y = top + h * 0.28
    # gradiente oro verticale
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    c1, c2, c3 = (250, 223, 135), (212, 168, 60), (150, 105, 25)
    for y in range(S):
        f = y / S
        if f < 0.5:
            k = f / 0.5; c = tuple(int(c1[i] + (c2[i] - c1[i]) * k) for i in range(3))
        else:
            k = (f - 0.5) / 0.5; c = tuple(int(c2[i] + (c3[i] - c2[i]) * k) for i in range(3))
        gd.line([(0, y), (S, y)], fill=c + (255,))
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    r = t * 0.12
    md.rounded_rectangle([cx - t / 2, top, cx + t / 2, top + h], radius=r, fill=255)
    md.rounded_rectangle([cx - w / 2, arm_y, cx + w / 2, arm_y + t], radius=r, fill=255)
    img.paste(grad, (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS)

def on_black(size, scale, round_=False):
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    if round_:
        d.ellipse([0, 0, size - 1, size - 1], fill=(0, 0, 0, 255))
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.18, fill=(0, 0, 0, 255))
    bg.alpha_composite(gold_cross(size, scale))
    return bg

res = sys.argv[1]
dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, k in dens.items():
    os.makedirs(f"{res}/mipmap-{name}", exist_ok=True)
    on_black(int(48 * k), 0.66).save(f"{res}/mipmap-{name}/ic_launcher.png")
    on_black(int(48 * k), 0.6, True).save(f"{res}/mipmap-{name}/ic_launcher_round.png")
    # primo piano per icona adattiva (108dp, zona sicura 66dp)
    gold_cross(int(108 * k), 0.5).save(f"{res}/mipmap-{name}/ic_launcher_foreground.png")
on_black(512, 0.66).save(os.path.join(os.path.dirname(os.path.abspath(res)), "icona-512.png"))

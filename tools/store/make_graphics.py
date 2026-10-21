#!/usr/bin/env python3
"""Draws the Play Store graphics from the app's own artwork and map data.

  icon-512.png             512 x 512: the launcher icon (flame over the playa horizon)
  feature-graphic.png      1024 x 500: the icon, name and tagline over the city's streets

Usage: make_graphics.py --out store/assets/graphics
       [--streets app/src/main/assets/playa/2026/geo/streets.geojson]
       [--fonts /usr/share/fonts/opentype/inter]

The flame shapes are the cubic paths of
app/src/main/res/drawable/ic_launcher_foreground.xml, in its 108-unit viewport.
"""
import argparse
import json
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

BACKGROUND = (0x2A, 0x1B, 0x12)
FLAME_OUTER = (0xFF, 0x8A, 0x1F)
FLAME_INNER = (0xFF, 0xD3, 0x4D)
HORIZON = (0xD9, 0xB9, 0x8C)

# ic_launcher_foreground.xml, as (start, [(c1, c2, end), ...]) cubic segments.
OUTER = ((54, 26), [((62, 38), (70, 44), (68, 58)), ((66, 70), (58, 76), (54, 76)),
                    ((50, 76), (42, 70), (40, 58)), ((38, 46), (48, 40), (54, 26))])
INNER = ((54, 46), [((58, 52), (62, 56), (61, 63)), ((60, 69), (57, 72), (54, 72)),
                    ((51, 72), (48, 69), (47, 63)), ((46, 57), (50, 52), (54, 46))])
BAR = ((24, 80), (84, 84))


def bezier(start, segments, steps=48):
    pts = [start]
    p0 = start
    for c1, c2, p3 in segments:
        for i in range(1, steps + 1):
            t = i / steps
            u = 1 - t
            pts.append(tuple(u ** 3 * a + 3 * u * u * t * b + 3 * u * t * t * c + t ** 3 * d
                             for a, b, c, d in zip(p0, c1, c2, p3)))
        p0 = p3
    return pts


def draw_mark(draw, ox, oy, scale):
    """The flame and horizon, viewport units mapped by (x - 0) * scale + ox."""
    def tx(pts):
        return [(ox + x * scale, oy + y * scale) for x, y in pts]
    draw.polygon(tx(bezier(*OUTER)), fill=FLAME_OUTER)
    draw.polygon(tx(bezier(*INNER)), fill=FLAME_INNER)
    (x0, y0), (x1, y1) = BAR
    draw.rectangle(tx([(x0, y0), (x1, y1)]), fill=HORIZON)


def icon(out, size=512, ss=4):
    big = size * ss
    img = Image.new("RGB", (big, big), BACKGROUND)
    # The launcher shows the inner 72 of the 108-unit canvas (adaptive icon safe zone).
    scale = big / 72
    draw_mark(ImageDraw.Draw(img), -18 * scale, -18 * scale, scale)
    img.resize((size, size), Image.LANCZOS).save(os.path.join(out, "icon-512.png"))


def streets_image(path, w, h, center, radius_px, color):
    """The city's street lines, projected around the Man at [center]."""
    with open(path, encoding="utf-8") as f:
        feats = json.load(f)["features"]
    lines = [f["geometry"]["coordinates"] for f in feats if f["geometry"]["type"] == "LineString"]
    lats = [p[1] for l in lines for p in l]
    lons = [p[0] for l in lines for p in l]
    lat0 = (max(lats) + min(lats)) / 2
    lon0 = (max(lons) + min(lons)) / 2
    k = math.cos(math.radians(lat0))
    extent = max((max(lons) - min(lons)) * k, max(lats) - min(lats)) / 2
    s = radius_px / extent
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for l in lines:
        pts = [(center[0] + (x - lon0) * k * s, center[1] - (y - lat0) * s) for x, y in l]
        d.line(pts, fill=color, width=3)
    return layer


def feature(out, streets, fonts, w=1024, h=500, ss=2):
    W, H = w * ss, h * ss
    img = Image.new("RGB", (W, H))
    # Dusk over the playa: deep violet at the top to warm dust at the horizon.
    top, bottom = (0x1E, 0x12, 0x24), (0x5A, 0x30, 0x1C)
    px = img.load()
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(a + (b - a) * t ** 1.4) for a, b in zip(top, bottom))
        for x in range(W):
            px[x, y] = c
    city = streets_image(streets, W, H, center=(int(W * 0.74), int(H * 0.56)), radius_px=H * 0.62,
                         color=(0xD9, 0xB9, 0x8C, 70))
    img.paste(city, (0, 0), city)
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((W * 0.05, H * 0.12, W * 0.33, H * 0.88), fill=(0xFF, 0x8A, 0x1F, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(60 * ss))
    img.paste(glow, (0, 0), glow)

    d = ImageDraw.Draw(img)
    mark = H * 0.64 / 58  # flame + bar span 26..84 in viewport units
    draw_mark(d, W * 0.19 - 54 * mark, H * 0.5 - 55 * mark, mark)

    title = ImageFont.truetype(os.path.join(fonts, "InterDisplay-Bold.otf"), 128 * ss)
    tag = ImageFont.truetype(os.path.join(fonts, "Inter-Medium.otf"), 38 * ss)
    x = W * 0.36
    d.text((x, H * 0.30), "ZepDriver", font=title, fill=(0xFF, 0xF4, 0xE6))
    d.text((x + 6 * ss, H * 0.62), "Your art car co-pilot on the playa", font=tag, fill=(0xF2, 0xC9, 0x8E))
    img.resize((w, h), Image.LANCZOS).save(os.path.join(out, "feature-graphic.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--streets", default="app/src/main/assets/playa/2026/geo/streets.geojson")
    ap.add_argument("--fonts", default="/usr/share/fonts/opentype/inter")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    icon(a.out)
    feature(a.out, a.streets, a.fonts)
    print("wrote", sorted(os.listdir(a.out)))


if __name__ == "__main__":
    main()

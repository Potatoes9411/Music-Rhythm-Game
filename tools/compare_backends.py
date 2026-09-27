#!/usr/bin/env python3
"""Pixel parity between the Android Canvas backend (Robolectric native Skia) and the desktop
Java2D reference renderer for identical scenes.

Usage: tools/compare_backends.py <android-dir> <java2d-dir> <out-csv> [sheet.png]

Metrics per frame (both 540x960 or 960x540):
  mad      mean absolute RGB difference (0-255) at full resolution
  mad_q    the same after 8x box downsampling (ignores anti-aliasing / glyph differences)
  diff32   fraction of pixels where any channel differs by more than 32
"""
import csv, os, sys
from PIL import Image, ImageChops, ImageStat

def metrics(a, b):
    a = a.convert("RGB"); b = b.convert("RGB").resize(a.size)
    d = ImageChops.difference(a, b)
    mad = sum(ImageStat.Stat(d).mean) / 3
    small = (max(1, a.width // 8), max(1, a.height // 8))
    dq = ImageChops.difference(a.resize(small, Image.BOX), b.resize(small, Image.BOX))
    mad_q = sum(ImageStat.Stat(dq).mean) / 3
    px = d.getdata()
    over = sum(1 for p in px if max(p) > 32) / (a.width * a.height)
    return mad, mad_q, over

def main():
    adir, jdir, out = sys.argv[1:4]
    sheet_path = sys.argv[4] if len(sys.argv) > 4 else None
    rows, pairs = [], []
    for name in sorted(os.listdir(adir)):
        if not name.endswith(".png"): continue
        j = os.path.join(jdir, name)
        if not os.path.exists(j): continue
        a = Image.open(os.path.join(adir, name)); b = Image.open(j)
        mad, mad_q, over = metrics(a, b)
        rows.append((name[:-4], round(mad, 2), round(mad_q, 2), round(over, 4)))
        pairs.append((name, a, b))
    with open(out, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["frame", "mad", "mad_q", "diff32"]); w.writerows(rows)
    worst = sorted(rows, key=lambda r: -r[2])
    print(f"{len(rows)} frames; mean mad_q {sum(r[2] for r in rows)/len(rows):.2f}; worst:")
    for r in worst[:8]: print("  ", r)
    if sheet_path:
        pick = [p for p in pairs if p[0].split("_")[0] in {w[0].split("_")[0] for w in worst[:6]}][:6]
        tw = 270
        cells = []
        for name, a, b in pick:
            h = int(a.height * tw / a.width)
            cells.append((a.convert("RGB").resize((tw, h)), b.convert("RGB").resize((tw, h))))
        H = max(c[0].height for c in cells)
        sheet = Image.new("RGB", (len(cells) * (2 * tw + 16), H), (60, 60, 60))
        for i, (a, b) in enumerate(cells):
            sheet.paste(a, (i * (2 * tw + 16), 0)); sheet.paste(b, (i * (2 * tw + 16) + tw + 2, 0))
        sheet.save(sheet_path)

if __name__ == "__main__":
    main()

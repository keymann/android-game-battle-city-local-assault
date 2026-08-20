#!/usr/bin/env python3
"""아틀라스를 만드는 공용 도구.

전달받은 에셋 팩은 세 갈래로 들어 있다.

    components_sheet_rgba.png   원본 해상도, 배경이 빠진 판   <- 이걸 쓴다
    components_atlas_128.png    128 격자로 다시 앉힌 판
    components_atlas.json       이름과 순서

128 격자판은 스프라이트 간격이 실제로 120px 남짓이라 뒤쪽 칸이 밀려 잘린다.
그래서 그림은 원본 판에서 직접 찾아 쓰고, **이름과 순서만** JSON 에서 가져온다.

PIL 은 이 기계에서 아키텍처가 맞지 않아 붙지 않는다. 픽셀은 ImageMagick 으로만 만진다.
"""

import json
import os
import subprocess

WORK = "build/atlas"
SPRITES = os.path.join(WORK, "sprites")


def run(args):
    subprocess.run([str(a) for a in args], check=True)


def size_of(path):
    out = subprocess.run(["magick", "identify", "-format", "%w %h", path],
                         capture_output=True, text=True).stdout.split()
    return int(out[0]), int(out[1])


def alpha_map(path):
    """알파 채널을 바이트 배열로. (raw, 너비, 높이)"""
    raw = subprocess.run(["magick", path, "-alpha", "extract", "-depth", "8", "gray:-"],
                         capture_output=True).stdout
    w, h = size_of(path)
    return raw, w, h


def rgba_map(path):
    raw = subprocess.run(["magick", path, "-depth", "8", "rgba:-"],
                         capture_output=True).stdout
    w, h = size_of(path)
    return raw, w, h


def _runs(flags):
    out, start = [], None
    for i, v in enumerate(flags):
        if v and start is None:
            start = i
        elif not v and start is not None:
            out.append((start, i))
            start = None
    if start is not None:
        out.append((start, len(flags)))
    return out


def find_cells(path, threshold=24, min_side=16):
    """판에서 스프라이트 하나하나의 자리를 찾는다.

    격자 수치를 믿지 않고 **빈 줄** 로 가른다. 시트마다 여백이 제각각이고
    간격이 정수로 떨어지지도 않아서, 눈금을 세는 쪽이 늘 어긋난다.

    @return 위에서 아래, 왼쪽에서 오른쪽 순서의 (x0, y0, x1, y1) 목록
    """
    raw, w, h = alpha_map(path)
    row_used = [any(raw[y * w + x] > threshold for x in range(w)) for y in range(h)]

    boxes = []
    for y0, y1 in _runs(row_used):
        if y1 - y0 < min_side:
            continue
        col_used = [any(raw[y * w + x] > threshold for y in range(y0, y1)) for x in range(w)]
        for x0, x1 in _runs(col_used):
            if x1 - x0 < min_side:
                continue
            top, bottom = y1, y0
            for y in range(y0, y1):
                base = y * w
                if any(raw[base + x] > threshold for x in range(x0, x1)):
                    top = min(top, y)
                    bottom = max(bottom, y + 1)
            boxes.append((x0, top, x1, bottom))
    return boxes


def names_of(kind):
    """에셋 팩이 정한 이름과 순서."""
    with open(f"assets/sprites/{kind}/components_atlas.json", encoding="utf-8") as f:
        return [s["name"] for s in json.load(f)["sprites"]]


def sheet_of(kind):
    return f"assets/sprites/{kind}/components_sheet_rgba.png"


# ---------------------------------------------------------------------------
# 자르기
# ---------------------------------------------------------------------------

def crop(sheet, box, out):
    x0, y0, x1, y1 = box
    run(["magick", sheet, "-crop", f"{x1 - x0}x{y1 - y0}+{x0}+{y0}", "+repage",
         "-depth", "8", out])
    return out


def tile_inset(path, limit=20, stable=3, tolerance=0.18):
    """이어 붙는 타일에서 가장자리를 몇 px 깎아야 하는지 잰다.

    이 팩의 타일은 **스티커** 모양이다. 모서리가 둥글고 바깥에 윤곽선이 둘려 있다.
    그대로 이어 붙이면 화면 전체에 격자 무늬가 생긴다. 눈으로 어림잡지 않고
    두 조건을 함께 만족하는 첫 지점을 찾는다.

      - 테두리 한 줄이 전부 불투명하다        (둥근 모서리가 사라졌다)
      - 그 줄의 밝기가 속과 비슷하다           (윤곽선이 사라졌다)

    밝기는 위아래 양쪽으로 본다. 윤곽선이 늘 어두운 것은 아니다. 물 타일은 바깥에
    **밝은** 물결 테두리가 둘려 있어 "어두우면 깎는다" 로는 하나도 못 깎는다.

    한 줄만 보고 끝내지 않고 [stable] 줄이 내리 통과해야 인정한다. 물 테두리는
    점선이라 어쩌다 한 줄이 속과 비슷해 보이는 일이 있다.
    """
    raw, w, h = rgba_map(path)

    def lum(x, y):
        i = (y * w + x) * 4
        if raw[i + 3] < 250:
            return None
        return (raw[i] * 299 + raw[i + 1] * 587 + raw[i + 2] * 114) / 1000

    inner = [v for y in range(h // 4, h - h // 4) for x in range(w // 4, w - w // 4)
             if (v := lum(x, y)) is not None]
    if not inner:
        return 0
    reference = sum(inner) / len(inner)

    def ring_ok(k):
        if w - 2 * k < 8 or h - 2 * k < 8:
            return False
        ring = []
        for x in range(k, w - k):
            for y in (k, h - 1 - k):
                v = lum(x, y)
                if v is None:
                    return False
                ring.append(v)
        for y in range(k, h - k):
            for x in (k, w - 1 - k):
                v = lum(x, y)
                if v is None:
                    return False
                ring.append(v)
        return ring and abs(sum(ring) / len(ring) - reference) <= reference * tolerance

    for k in range(limit):
        if all(ring_ok(k + d) for d in range(stable)):
            return k
    return limit


def make_tile(sheet, box, out, size, inset):
    """이어 붙는 타일. 테두리를 깎고 정확히 정사각으로 늘린다."""
    crop(sheet, box, out)
    if inset:
        run(["magick", out, "-shave", f"{inset}x{inset}", "+repage", out])
    run(["magick", out, "-filter", "Box", "-resize", f"{size}x{size}!",
         "-alpha", "off", "-depth", "8", out])
    return out


def make_block(sheet, box, out, size):
    """벽처럼 한 칸을 통째로 차지하는 물건. 윤곽선이 곧 생김새라 깎지 않는다."""
    crop(sheet, box, out)
    run(["magick", out, "-filter", "Box", "-resize", f"{size}x{size}!",
         "-depth", "8", out])
    return out


def make_object(sheet, box, out, longest):
    """하나씩 놓이는 물건. 비율을 지켜 줄인다."""
    crop(sheet, box, out)
    run(["magick", out, "-filter", "Box", "-resize", f"{longest}x{longest}",
         "-depth", "8", out])
    return out


def make_framed(sheet, boxes, outs, width, height, gravity="south"):
    """여러 장이 한 자리에서 이어지는 그림. 프레임마다 따로 다듬으면 들썩인다.

    가장 큰 칸을 기준으로 배율을 한 번만 정하고, 모두 같은 캔버스에 같은 방식으로
    얹는다. 본진 파괴 단계나 폭발 프레임처럼 같은 곳에서 이어 재생되는 그림에 쓴다.
    """
    span_w = max(b[2] - b[0] for b in boxes)
    span_h = max(b[3] - b[1] for b in boxes)
    scale = min(width / span_w, height / span_h)

    for box, out in zip(boxes, outs):
        crop(sheet, box, out)
        w, h = box[2] - box[0], box[3] - box[1]
        run(["magick", out, "-filter", "Box",
             "-resize", f"{max(1, round(w * scale))}x{max(1, round(h * scale))}!",
             "-background", "none", "-gravity", gravity,
             "-extent", f"{width}x{height}", "-depth", "8", out])
    return outs


def body_center(path, horizontal, ratio=0.7):
    """차체(궤도)의 한가운데와 크기를 잰다.

    탱크는 방향마다 그림이 따로 그려져 있다. 그런데 포신이 뻗은 쪽으로 그림이
    길어지므로, 그림 전체의 한가운데를 맞추면 방향을 꺾을 때마다 탱크가 튄다.

    포신이 뻗은 축과 그렇지 않은 축을 다르게 본다.

      포신과 **직각인** 축  : 궤도가 끝에서 끝까지 차 있다. 전체 테두리가 곧 차체다.
      포신과 **나란한** 축  : 포신 때문에 길어져 있다. 궤도가 있는 구간만 골라낸다.
                            궤도가 있는 줄은 포신만 있는 줄보다 훨씬 두껍다.

    처음에는 축을 가리지 않고 "두꺼운 줄" 만 찾았는데, 좌우를 보는 탱크에서는
    포신이 가로지르는 줄이 가장 넓어서 그 한 줄만 차체로 잡혔다. 그래서 배율이
    터무니없이 커졌다.

    @return (중심x, 중심y, 차체크기)
    """
    raw, w, h = alpha_map(path)
    row_width, col_height = [], []
    for y in range(h):
        xs = [x for x in range(w) if raw[y * w + x] > 24]
        row_width.append(xs[-1] - xs[0] + 1 if xs else 0)
    for x in range(w):
        ys = [y for y in range(h) if raw[y * w + x] > 24]
        col_height.append(ys[-1] - ys[0] + 1 if ys else 0)

    def bounds(values):
        used = [i for i, v in enumerate(values) if v > 0]
        return (used[0], used[-1]) if used else (0, len(values) - 1)

    def band(values):
        peak = max(values)
        keep = [i for i, v in enumerate(values) if v >= peak * ratio]
        return (keep[0], keep[-1]) if keep else bounds(values)

    if horizontal:
        left, right = band(col_height)       # 포신과 나란한 축
        top, bottom = bounds(row_width)      # 포신과 직각인 축
        body = bottom - top + 1
    else:
        left, right = bounds(col_height)
        top, bottom = band(row_width)
        body = right - left + 1

    return ((left + right) / 2, (top + bottom) / 2, body)


def make_tank(sheet, box, out, body_px, canvas):
    """차체 한가운데를 캔버스 한가운데에 맞추고, 차체 너비를 한 블록으로 맞춘다."""
    crop(sheet, box, out)
    horizontal = out.endswith("_right.png") or out.endswith("_left.png")
    cx, cy, body = body_center(out, horizontal)
    scale = body_px / body

    w, h = size_of(out)
    run(["magick", out, "-filter", "Box",
         "-resize", f"{max(1, round(w * scale))}x{max(1, round(h * scale))}!",
         "-depth", "8", out])
    run(["magick", "-size", f"{canvas}x{canvas}", "xc:none", out,
         "-geometry", f"{round(canvas / 2 - cx * scale):+d}{round(canvas / 2 - cy * scale):+d}",
         "-composite", "-depth", "8", out])
    return out


# ---------------------------------------------------------------------------
# 한 장으로 묶기
# ---------------------------------------------------------------------------

def pack(entries, out_png, out_xml, width, padding=1):
    """선반 채우기. 키 순으로 줄을 세워 왼쪽부터 채운다.

    스프라이트 사이에 한 줄을 비운다. 화면에서 확대될 때 옆 그림의 색이 딸려
    들어오는 것(bleeding)을 막는다.

    @param entries (이름, 파일경로) 목록
    """
    sized = [(name, path) + size_of(path) for name, path in entries]
    sized.sort(key=lambda e: (-e[3], e[0]))

    placed, x, y, row_h = [], padding, padding, 0
    for name, path, w, h in sized:
        if x + w + padding > width:
            x = padding
            y += row_h + padding
            row_h = 0
        placed.append((name, path, x, y, w, h))
        x += w + padding
        row_h = max(row_h, h)
    height = y + row_h + padding

    args = ["magick", "-size", f"{width}x{height}", "xc:none"]
    for _name, path, px, py, _w, _h in placed:
        args += [path, "-geometry", f"+{px}+{py}", "-composite"]
    args += ["-depth", "8", "-strip", out_png]
    run(args)

    with open(out_xml, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write("<!-- tools/build_atlas.py 가 생성한다. 직접 수정하지 말 것. -->\n")
        f.write('<TextureAtlas imagePath="game.png">\n')
        for name, _path, px, py, w, h in sorted(placed):
            f.write(f'\t<SubTexture name="{name}.png" x="{px}" y="{py}" '
                    f'width="{w}" height="{h}"/>\n')
        f.write("</TextureAtlas>\n")

    print(f"아틀라스 {width}x{height}, 스프라이트 {len(placed)}장 -> {out_png}")
    return width, height

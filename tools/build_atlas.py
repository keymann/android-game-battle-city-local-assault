#!/usr/bin/env python3
"""게임이 쓰는 아틀라스 한 장을 만든다.

    assets/sprites/{terrain,tanks,effects}   원본 판 + 이름표(JSON)
    assets/hud/components                    HUD 아이콘
    assets/lobby/components                  로비 아이콘
    assets/font/font_sheet.png               비트맵 폰트 글리프
        -> app/src/main/assets/atlas/game.png / game.xml

에셋 팩 전체가 같은 화풍이고 전부 nearest 로 그리므로 아틀라스를 나눌 이유가 없다.
한 장이면 텍스처 교체가 한 번도 일어나지 않는다.

폰트만 이 팩에 없다. 글자는 계속 필요하므로 Kenney Desert Shooter 글리프를
별도 시트로 두고 함께 얹는다. (CC0, assets/font/README.md)

사용법: tools/build_atlas.py
"""

import json
import os
import shutil
import sys

sys.path.insert(0, "tools")
import atlaslib as A  # noqa: E402

OUT_DIR = "app/src/main/assets/atlas"
FONT_SHEET = "assets/font/font_sheet.png"
FONT_LAYOUT = "assets/font/font_sheet.json"

#: 지형 타일 한 장이 한 블록이다. 탱크와 같은 크기다.
TILE = 64
#: 하나씩 놓이는 소품.
PROP = 56
#: 본진은 블록보다 조금 크게 서서 위압감을 준다.
BASE_W, BASE_H = 72, 64
#: 탱크 차체는 정확히 한 블록. 포신이 밖으로 뻗으므로 캔버스는 더 넓다.
TANK_BODY, TANK_CANVAS = 64, 96
#: 폭발·방어막·대시는 탱크를 감싸야 하므로 넉넉하게.
BIG_FX = 96
#: 포탄과 총구 화염.
SHELL, FLASH, IMPACT = 40, 48, 56
HUD = 64
#: 로비는 게임 화면보다 크게 그린다. 손가락으로 누를 것들이라 작으면 안 된다.
LOBBY = 96

ATLAS_WIDTH = 1024

#: 이어 붙어야 하는 갈래. 스티커 테두리를 깎고 정사각으로 늘린다.
SEAMLESS = ("ground_", "water_", "ice_")
#: 한 칸을 통째로 차지하는 갈래. 윤곽선이 곧 생김새라 그대로 둔다.
BLOCKY = ("brick_intact", "brick_damage", "steel_", "forest_")


def family_of(name):
    return name.split("_")[0]


def build_terrain(entries):
    sheet = A.sheet_of("terrain")
    names = A.names_of("terrain")
    boxes = A.find_cells(sheet)
    by_name = dict(zip(names, boxes))

    # 이어 붙는 갈래는 갈래마다 같은 두께로 깎는다. 타일마다 실측값을 따로 쓰면
    # 물 네 장이 서로 다른 배율로 줄어들어 물결 굵기가 칸마다 달라 보인다.
    insets = {}
    for name in names:
        if not name.startswith(SEAMLESS):
            continue
        probe = A.crop(sheet, by_name[name], f"{A.SPRITES}/_probe.png")
        family = family_of(name)
        insets[family] = max(insets.get(family, 0), A.tile_inset(probe))

    base_names = [n for n in names if n.startswith("base_")]
    A.make_framed(sheet, [by_name[n] for n in base_names],
                  [f"{A.SPRITES}/{n}.png" for n in base_names],
                  BASE_W, BASE_H, gravity="south")
    for name in base_names:
        entries.append((name, f"{A.SPRITES}/{name}.png"))

    for name in names:
        if name.startswith("base_"):
            continue
        out = f"{A.SPRITES}/{name}.png"
        box = by_name[name]
        if name.startswith(SEAMLESS):
            A.make_tile(sheet, box, out, TILE, insets[family_of(name)] + 1)
        elif name.startswith(BLOCKY):
            A.make_block(sheet, box, out, TILE)
        else:
            A.make_object(sheet, box, out, PROP)
        entries.append((name, out))


def build_tanks(entries):
    """탱크를 **무채색**으로 넣는다.

    사람마다 색을 고르기 때문이다(로비에서 고른다). 색이 박힌 그림에 다른 색을
    곱하면 탁해진다. 무채색으로 두고 그릴 때 곱하면 어떤 색이든 깨끗하게 나온다.

    색공간을 Gray 로 바꾼 뒤 반드시 sRGB 로 되돌린다. Gray 로 남겨 두면 나중에
    색을 곱해도 회색으로 뭉개진다.
    """
    sheet = A.sheet_of("tanks")
    boxes = A.find_cells(sheet)
    for name, box in zip(A.names_of("tanks"), boxes):
        out = f"{A.SPRITES}/{name}.png"
        A.make_tank(sheet, box, out, TANK_BODY, TANK_CANVAS)
        A.run(["magick", out, "-colorspace", "Gray", "-colorspace", "sRGB",
               # 살짝 밝혀 둔다. 색을 곱하면 어두워지기 때문이다.
               "-level", "0%,92%", "-depth", "8", out])
        entries.append((name, out))


def build_effects(entries):
    sheet = A.sheet_of("effects")
    names = A.names_of("effects")
    by_name = dict(zip(names, A.find_cells(sheet)))

    # 같은 자리에서 이어 재생되는 그림은 프레임끼리 크기를 맞춰야 들썩이지 않는다.
    for prefix in ("explosion_", "shield_", "dash_trail_"):
        group = [n for n in names if n.startswith(prefix)]
        A.make_framed(sheet, [by_name[n] for n in group],
                      [f"{A.SPRITES}/{n}.png" for n in group],
                      BIG_FX, BIG_FX, gravity="center")
        for name in group:
            entries.append((name, f"{A.SPRITES}/{name}.png"))

    single = {"spawn_glow": BIG_FX, "base_warning_pulse": BIG_FX,
              "impact_small": IMPACT, "ricochet_steel": IMPACT,
              "debris_brick": IMPACT, "shield_hit": IMPACT}
    for name in names:
        if name.startswith(("explosion_", "shield_", "dash_trail_")):
            continue
        longest = single.get(name)
        if longest is None:
            longest = SHELL if name.startswith("shell_") else FLASH
        out = f"{A.SPRITES}/{name}.png"
        A.make_object(sheet, by_name[name], out, longest)
        entries.append((name, out))


#: 쓰지 않는 아이콘. 쿨타임은 SPECIAL 버튼 자체에 표현하므로 따로 둘 것이 없다.
SKIP_ICONS = {"cooldown_25", "cooldown_75"}


def build_icons(entries, src, prefix, size):
    """아이콘 묶음. 개별 파일은 잘림이 제각각이라 한 번 더 다듬는다."""
    for filename in sorted(os.listdir(src)):
        if not filename.endswith(".png"):
            continue
        if filename[:-4] in SKIP_ICONS:
            continue
        name = f"{prefix}{filename[:-4]}"
        out = f"{A.SPRITES}/{name}.png"
        A.run(["magick", os.path.join(src, filename), "-trim", "+repage",
               "-filter", "Box", "-resize", f"{size}x{size}", "-depth", "8", out])
        entries.append((name, out))


def build_font(entries):
    """비트맵 폰트를 얹는다.

    게임 에셋 팩에는 글자가 없다. HUD 는 `P1` `ENEMY 32/80` 같은 글자를 계속 써야
    하므로 Kenney Desert Shooter 글리프를 별도 시트로 두고 여기서 가져온다.
    (assets/font/README.md)
    """
    if not os.path.exists(FONT_LAYOUT):
        print("경고: 폰트 시트가 없어 글자를 넣지 못했다")
        return
    with open(FONT_LAYOUT, encoding="utf-8") as f:
        layout = json.load(f)

    for glyph in layout["glyphs"]:
        name = glyph["name"]
        out = f"{A.SPRITES}/{name}.png"
        A.crop(FONT_SHEET,
               (glyph["x"], glyph["y"], glyph["x"] + glyph["w"], glyph["y"] + glyph["h"]),
               out)
        entries.append((name, out))
    print(f"폰트 글리프 {len(layout['glyphs'])}장")


def main():
    if os.path.isdir(A.SPRITES):
        shutil.rmtree(A.SPRITES)
    os.makedirs(A.SPRITES, exist_ok=True)

    entries = []
    build_terrain(entries)
    build_tanks(entries)
    build_effects(entries)
    build_icons(entries, "assets/hud/components", "hud_", HUD)
    build_icons(entries, "assets/lobby/components", "lobby_", LOBBY)
    build_font(entries)

    entries = [(n, p) for n, p in entries if not n.startswith("_")]
    A.pack(entries, os.path.join(OUT_DIR, "game.png"),
           os.path.join(OUT_DIR, "game.xml"), ATLAS_WIDTH)


if __name__ == "__main__":
    main()

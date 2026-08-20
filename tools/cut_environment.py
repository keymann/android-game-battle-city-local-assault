#!/usr/bin/env python3
"""환경 오브젝트 시트에서 게임에 쓸 스프라이트를 잘라내고 아틀라스 구역을 만든다.

전달받은 `assets/renewal.png` 는 라벨과 색상 팔레트가 함께 든 **설명용 시트**다.
좌표는 시트를 실측한 값이다.

두 종류를 다르게 다룬다.

* **tile**  — 지형처럼 빈틈없이 이어 붙어야 한다. 바깥의 어두운 테두리를 안쪽으로
  잘라 내지 않으면 화면에 격자 무늬가 생긴다. 정확히 32x32 로 맞춘다.
* **object** — 드럼통·건물처럼 하나씩 놓이는 물건. 테두리가 윤곽선 역할을 하므로
  그대로 두고, 비율을 지켜 지정한 크기 안에 넣는다.

결과물:
    <출력>/sprites/<이름>.png   개별 스프라이트
    <출력>/env.png              아틀라스에 붙일 구역 이미지
    <출력>/layout.json          구역 안에서의 좌표 (서술자 생성기가 읽는다)

사용법: cut_environment.py <시트> <출력 디렉터리> <구역 가로>
"""

import json
import os
import subprocess
import sys

TILE_SIZE = 32
ROW_HEIGHT = 48

# (이름, x0, y0, x1, y1, 종류, 값)
#   종류 tile / tile_rot90 : 값 = 잘라 낼 테두리 두께(원본 픽셀). 항상 TILE_SIZE 정사각.
#   종류 object            : 값 = 긴 변의 목표 크기. 비율 유지 축소.
#
# 테두리 두께는 종류마다 다르다.
#   바닥/도로 : 두껍게 잘라 낸다. 조금이라도 남으면 화면 전체에 격자가 생긴다.
#              y 좌표는 줄 전체가 아니라 타일의 실제 세로 범위를 잰 값이다.
#              줄 범위를 그대로 쓰면 아래쪽 빈 칸이 딸려 와 가로 이음선이 생긴다.
#   물        : 바깥의 밝은 물결을 조금 남긴다. 셀 사이가 잔물결처럼 보인다.
#   벽돌/강철 : 테두리가 벽 블록의 윤곽이라 살짝만 다듬는다.
SPRITES = [
    # --- 벽돌 벽 (y 175~263) ---------------------------------------------
    ("env_brick_0", 21, 178, 99, 261, "tile", 5),
    ("env_brick_1", 110, 178, 187, 261, "tile", 5),
    ("env_brick_2", 198, 178, 271, 261, "tile", 5),
    ("env_brick_rubble_0", 282, 175, 358, 263, "object", 32),
    ("env_brick_rubble_1", 368, 175, 430, 263, "object", 32),

    # --- 강철 벽 ---------------------------------------------------------
    ("env_steel_0", 457, 178, 530, 261, "tile", 5),
    ("env_steel_1", 540, 178, 612, 261, "tile", 5),
    ("env_steel_2", 623, 178, 695, 261, "tile", 5),
    ("env_steel_3", 704, 178, 777, 261, "tile", 5),
    ("env_steel_rubble", 785, 175, 871, 263, "object", 32),

    # --- 물 --------------------------------------------------------------
    ("env_water_0", 908, 178, 996, 261, "tile", 6),
    ("env_water_1", 1007, 178, 1097, 261, "tile", 6),
    ("env_water_2", 1098, 178, 1188, 261, "tile", 6),
    ("env_well", 1200, 175, 1290, 263, "object", 32),

    # --- 나무 / 풀 (y 347~438) -------------------------------------------
    ("env_tree_0", 20, 347, 99, 438, "object", 40),
    ("env_tree_1", 110, 347, 191, 438, "object", 40),
    ("env_bush", 203, 347, 280, 438, "object", 36),
    ("env_stump_0", 293, 347, 356, 438, "object", 26),
    ("env_flower", 368, 347, 432, 438, "object", 26),

    # --- 바닥 / 도로 (여섯 칸이 붙어 있어 균등 분할) ----------------------
    ("env_ground_dirt", 457, 349, 527, 424, "tile", 9),
    ("env_ground_grass", 527, 349, 598, 424, "tile", 9),
    ("env_ground_sand", 598, 349, 668, 424, "tile", 9),
    ("env_road_0", 668, 349, 739, 424, "tile", 9),
    ("env_road_1", 739, 349, 809, 424, "tile", 9),
    ("env_road_line", 809, 349, 880, 424, "tile", 9),
    # 가로 도로용. 세로 중앙선 타일을 90도 돌려 쓴다.
    # StageData 는 스프라이트 인덱스만 들고 회전값이 없어, 아틀라스에 미리 넣어 둔다.
    ("env_road_line_h", 809, 349, 880, 424, "tile_rot90", 9),

    # --- 진지 / 모래주머니 ------------------------------------------------
    ("env_sandbag_0", 908, 347, 1007, 438, "object", 36),
    ("env_sandbag_1", 1022, 347, 1093, 438, "object", 32),
    ("env_barricade_0", 1105, 347, 1174, 438, "object", 32),
    ("env_barricade_1", 1184, 347, 1284, 438, "object", 36),

    # --- 드럼통 / 유류 탱크 (y 530~618) ----------------------------------
    ("env_barrel_red", 28, 530, 78, 618, "object", 28),
    ("env_barrel_blue", 97, 530, 150, 618, "object", 28),
    ("env_barrel_green", 169, 530, 219, 618, "object", 28),
    ("env_barrel_yellow", 239, 530, 287, 618, "object", 28),
    ("env_barrel_fuel", 307, 530, 363, 618, "object", 28),
    ("env_barrel_wood", 388, 530, 437, 618, "object", 28),

    # --- 상자 / 보급품 ----------------------------------------------------
    ("env_crate_0", 463, 530, 530, 618, "object", 30),
    ("env_crate_1", 547, 530, 619, 618, "object", 30),
    ("env_crate_broken", 636, 530, 702, 618, "object", 30),
    ("env_supply_blue", 720, 530, 791, 618, "object", 30),
    ("env_supply_medic", 808, 530, 875, 618, "object", 30),

    # --- 울타리 / 장애물 --------------------------------------------------
    ("env_fence_0", 909, 530, 996, 618, "object", 32),
    ("env_fence_1", 1012, 530, 1094, 618, "object", 32),
    ("env_barrier_yellow", 1111, 530, 1188, 618, "object", 32),
    ("env_barrier_red", 1208, 530, 1291, 618, "object", 32),

    # --- 건물 / 구조물 (y 700~871) ---------------------------------------
    ("env_building_house", 21, 700, 130, 871, "object", 48),
    ("env_building_blue", 142, 700, 237, 871, "object", 48),
    ("env_building_office", 250, 700, 351, 871, "object", 48),
    ("env_building_radar", 370, 700, 460, 871, "object", 48),

    # --- 탑 / 초소 --------------------------------------------------------
    ("env_tower_wood", 510, 700, 604, 871, "object", 48),
    ("env_tower_green", 629, 700, 714, 871, "object", 48),
    ("env_tower_light", 736, 700, 815, 871, "object", 48),

    # --- 장식 오브젝트 ----------------------------------------------------
    ("env_lamp", 901, 700, 960, 871, "object", 40),
    ("env_flag_red", 984, 700, 1057, 871, "object", 40),
    ("env_flag_blue", 1065, 700, 1131, 871, "object", 40),
    ("env_sign", 1152, 700, 1228, 871, "object", 34),
    ("env_stump_1", 1236, 700, 1292, 871, "object", 28),

    # --- 특수 오브젝트 (y 953~1065) --------------------------------------
    ("env_bridge_wood", 21, 953, 127, 1065, "object", 44),
    ("env_bridge_metal", 139, 953, 241, 1065, "object", 44),
    ("env_crops", 256, 953, 351, 1065, "object", 40),
    ("env_crater", 368, 953, 461, 1065, "object", 40),
    ("env_tire", 474, 953, 529, 1065, "object", 24),
    ("env_tire_stack", 546, 953, 616, 1065, "object", 32),
]


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 1

    sheet, out_dir, section_width = sys.argv[1], sys.argv[2], int(sys.argv[3])
    sprite_dir = os.path.join(out_dir, "sprites")
    os.makedirs(sprite_dir, exist_ok=True)

    sizes = {}
    for name, x0, y0, x1, y1, kind, target in SPRITES:
        path = os.path.join(sprite_dir, f"{name}.png")
        if kind in ("tile", "tile_rot90"):
            # 먼저 trim 으로 빈 여백을 걷어내 타일의 실제 경계를 찾고,
            # 거기서 테두리를 깎는다. 시트에서 잰 구역은 줄 전체를 감싸고 있어
            # 위아래로 빈 여백이 남는데, 그걸 두고 인셋만 주면 세로 이음선이 생긴다.
            w, h = x1 - x0, y1 - y0
            subprocess.run(
                ["magick", sheet, "-crop", f"{w}x{h}+{x0}+{y0}", "+repage",
                 "-trim", "+repage",
                 "-shave", f"{target}x{target}",
                 "-filter", "Box", "-resize", f"{TILE_SIZE}x{TILE_SIZE}!"]
                + (["-rotate", "90"] if kind == "tile_rot90" else [])
                + [path],
                check=True,
            )
            sizes[name] = (TILE_SIZE, TILE_SIZE)
        else:
            w, h = x1 - x0, y1 - y0
            subprocess.run(
                ["magick", sheet, "-crop", f"{w}x{h}+{x0}+{y0}", "+repage",
                 "-trim", "+repage",
                 "-filter", "Box", "-resize", f"{target}x{target}", path],
                check=True,
            )
            out = subprocess.run(["magick", "identify", "-format", "%w %h", path],
                                 capture_output=True, text=True).stdout.split()
            sizes[name] = (int(out[0]), int(out[1]))

    # --- 구역 이미지로 묶는다 ------------------------------------------
    # 높이가 제각각이라 격자로는 담을 수 없다. 왼쪽부터 채우고 넘치면 줄을 바꾼다.
    placements = []
    cursor_x, cursor_y = 0, 0
    for name, *_ in SPRITES:
        w, h = sizes[name]
        if cursor_x + w > section_width:
            cursor_x = 0
            cursor_y += ROW_HEIGHT
        placements.append((name, cursor_x, cursor_y, w, h))
        cursor_x += w

    section_height = cursor_y + ROW_HEIGHT
    args = ["magick", "-size", f"{section_width}x{section_height}", "xc:none"]
    for name, x, y, _w, _h in placements:
        args += [os.path.join(sprite_dir, f"{name}.png"), "-geometry", f"+{x}+{y}", "-composite"]
    args += ["-depth", "8", os.path.join(out_dir, "env.png")]
    subprocess.run(args, check=True)

    with open(os.path.join(out_dir, "layout.json"), "w", encoding="utf-8") as f:
        json.dump(
            {"height": section_height,
             "sprites": [{"name": n, "x": x, "y": y, "w": w, "h": h}
                         for n, x, y, w, h in placements]},
            f, ensure_ascii=False, indent=2,
        )

    print(f"환경 스프라이트 {len(SPRITES)}장 -> 구역 {section_width}x{section_height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

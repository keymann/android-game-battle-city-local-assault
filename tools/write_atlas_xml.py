#!/usr/bin/env python3
"""통합 아틀라스의 TextureAtlas 서술자를 만든다.

`build_tile_atlas.sh` 가 이미지들을 정해진 순서로 합쳐 놓았으므로,
여기서는 같은 순서로 좌표를 계산해 이름을 붙인다.

이름 접두사가 곧 출처다.
    battle_ / town_ / dungeon_   16px 타일
    ui_                          16px 인터페이스 (폰트는 사람이 읽는 이름)
    fx_                          24px 이펙트
    base_                        본진 파괴 애니메이션

사용법:
  write_atlas_xml.py <출력> <16px열수> <24px열수> <16px높이> <24px높이>
                     <본진가로> <본진세로> <본진장수>
"""

import sys

# 16px 구역에 들어가는 팩과 장수. 합친 순서와 같아야 한다.
SECTIONS_16 = [("battle", 198), ("town", 132), ("dungeon", 132), ("ui", 198)]

FX_COUNT = 40


def ui_names() -> dict:
    """Desert Shooter 인터페이스 시트의 폰트 구간에 읽을 수 있는 이름을 붙인다.

    알파벳은 한 줄에 이어지지 않는다. 시트가 18열이라 A~M 다음에 게이지 바가 끼고
    N~Z 가 다음 줄에서 시작한다. 26자를 연속으로 매기면 N~R 자리에 바가 들어온다.
    """
    names = {}
    for i, char in enumerate("0123456789"):
        names[93 + i] = f"ui_digit_{char}"
        names[147 + i] = f"ui_alt_digit_{char}"
    for i, char in enumerate("ABCDEFGHIJKLM"):
        names[108 + i] = f"ui_char_{char}"
        names[162 + i] = f"ui_alt_char_{char}"
    for i, char in enumerate("NOPQRSTUVWXYZ"):
        names[126 + i] = f"ui_char_{char}"
        names[180 + i] = f"ui_alt_char_{char}"
    names.update({90: "ui_percent", 91: "ui_plus", 92: "ui_minus",
                  144: "ui_alt_percent", 145: "ui_alt_plus", 146: "ui_alt_minus"})
    return names


def main() -> int:
    if len(sys.argv) != 9:
        print(__doc__)
        return 1

    out_path = sys.argv[1]
    cols16, cols24 = int(sys.argv[2]), int(sys.argv[3])
    part16_h, part24_h = int(sys.argv[4]), int(sys.argv[5])
    base_w, base_h, base_count = int(sys.argv[6]), int(sys.argv[7]), int(sys.argv[8])

    special = ui_names()
    entries = []

    index = 0
    for prefix, count in SECTIONS_16:
        for local in range(count):
            col, row = index % cols16, index // cols16
            name = special.get(local) if prefix == "ui" else None
            entries.append((name or f"{prefix}_{local:03d}", col * 16, row * 16, 16, 16))
            index += 1

    for local in range(FX_COUNT):
        col, row = local % cols24, local // cols24
        entries.append((f"fx_{local:03d}", col * 24, part16_h + row * 24, 24, 24))

    base_y = part16_h + part24_h
    for local in range(base_count):
        entries.append((f"base_{local}", local * base_w, base_y, base_w, base_h))

    with open(out_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write("<!-- tools/build_tile_atlas.sh 가 생성한다. 직접 수정하지 말 것. -->\n")
        f.write('<TextureAtlas imagePath="tiles.png">\n')
        for name, x, y, w, h in entries:
            f.write(f'\t<SubTexture name="{name}.png" x="{x}" y="{y}" width="{w}" height="{h}"/>\n')
        f.write("</TextureAtlas>\n")

    print(f"서술자 {len(entries)}개 항목 -> {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

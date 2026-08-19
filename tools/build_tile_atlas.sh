#!/usr/bin/env bash
#
# 픽셀아트 팩 4종을 하나의 아틀라스로 합친다.
#
#   assets/asset2                      Tiny Battle    198 x 16px  -> battle_*
#   assets/kenney_tiny-town            Tiny Town      132 x 16px  -> town_*
#   assets/kenney_tiny-dungeon         Tiny Dungeon   132 x 16px  -> dungeon_*
#   assets/kenney_desert-shooter-pack  Interface      198 x 16px  -> ui_*
#   assets/kenney_desert-shooter-pack  Weapons         40 x 24px  -> fx_*
#
# 결과물:
#   app/src/main/assets/atlas/tiles.png   단일 텍스처
#   app/src/main/assets/atlas/tiles.xml   Kenney TextureAtlas 형식 서술자
#
# 이름으로 조회하므로(예: town_052) 매니페스트가 숫자 인덱스 대신
# 출처가 드러나는 문자열을 쓴다. 아틀라스 배치가 바뀌어도 매니페스트는 그대로다.
#
# 사용법: tools/build_tile_atlas.sh

set -euo pipefail
cd "$(dirname "$0")/.."

OUT_DIR="app/src/main/assets/atlas"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

COLS_16=22   # 16px 구역의 가로 타일 수. 198 과 132 가 모두 나누어떨어진다.
COLS_24=14   # 24px 구역. 14 x 24 = 336 <= 352

BATTLE_DIR="assets/asset2/Tiles"
TOWN_DIR="assets/kenney_tiny-town/Tiles"
DUNGEON_DIR="assets/kenney_tiny-dungeon/Tiles"
UI_DIR="assets/kenney_desert-shooter-pack_1.0/PNG/Interface/Tiles"
FX_DIR="assets/kenney_desert-shooter-pack_1.0/PNG/Weapons/Tiles"

list_tiles() { ls "$1"/tile_*.png | sort; }

# --- 16px 구역 --------------------------------------------------------------
FILES_16=()
while IFS= read -r f; do FILES_16+=("$f"); done < <(
  list_tiles "$BATTLE_DIR"
  list_tiles "$TOWN_DIR"
  list_tiles "$DUNGEON_DIR"
  list_tiles "$UI_DIR"
)
echo "16px 타일 ${#FILES_16[@]}장"

magick montage "${FILES_16[@]}" \
  -tile "${COLS_16}x" -geometry '16x16+0+0' -background none \
  "$TMP/part16.png"

# --- 24px 구역 --------------------------------------------------------------
FILES_24=()
while IFS= read -r f; do FILES_24+=("$f"); done < <(list_tiles "$FX_DIR")
echo "24px 타일 ${#FILES_24[@]}장"

WIDTH=$((COLS_16 * 16))
magick montage "${FILES_24[@]}" \
  -tile "${COLS_24}x" -geometry '24x24+0+0' -background none \
  "$TMP/part24_raw.png"

# extent 는 montage 입력이 아니라 결과에 적용해야 한다.
# 입력에 걸면 각 타일이 352px 로 늘어난 뒤 24px 로 축소돼 그림이 뭉개진다.
PART24_H=$(magick identify -format '%h' "$TMP/part24_raw.png")
magick "$TMP/part24_raw.png" -background none -gravity northwest \
  -extent "${WIDTH}x${PART24_H}" "$TMP/part24.png"

# --- 합치기 -----------------------------------------------------------------
mkdir -p "$OUT_DIR"
magick "$TMP/part16.png" "$TMP/part24.png" -background none -append "$OUT_DIR/tiles.png"

PART16_H=$(magick identify -format '%h' "$TMP/part16.png")
TOTAL_W=$(magick identify -format '%w' "$OUT_DIR/tiles.png")
TOTAL_H=$(magick identify -format '%h' "$OUT_DIR/tiles.png")
echo "아틀라스 ${TOTAL_W}x${TOTAL_H} (16px 구역 높이 ${PART16_H})"

# --- 서술자 -----------------------------------------------------------------
# desert-shooter Interface 의 폰트 구간은 이름을 사람이 읽을 수 있게 붙인다.
python3 - "$OUT_DIR/tiles.xml" "$COLS_16" "$COLS_24" "$PART16_H" "$WIDTH" <<'PY'
import sys

out_path, cols16, cols24, part16_h, width = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])

sections16 = [("battle", 198), ("town", 132), ("dungeon", 132), ("ui", 198)]

# desert-shooter Interface 폰트 배치 (인덱스 -> 사람이 읽는 이름)
#
# 알파벳은 한 줄에 이어지지 않는다. 시트가 18열이라 A~M 다음에 게이지 바가 끼고
# N~Z 가 다음 줄에서 시작한다. 26자를 연속으로 매기면 N~R 자리에 바가 들어온다.
UI_NAMES = {}
for i, ch in enumerate("0123456789"):
    UI_NAMES[93 + i] = f"ui_digit_{ch}"
    UI_NAMES[147 + i] = f"ui_alt_digit_{ch}"
for i, ch in enumerate("ABCDEFGHIJKLM"):
    UI_NAMES[108 + i] = f"ui_char_{ch}"
    UI_NAMES[162 + i] = f"ui_alt_char_{ch}"
for i, ch in enumerate("NOPQRSTUVWXYZ"):
    UI_NAMES[126 + i] = f"ui_char_{ch}"
    UI_NAMES[180 + i] = f"ui_alt_char_{ch}"
UI_NAMES.update({90: "ui_percent", 91: "ui_plus", 92: "ui_minus",
                 144: "ui_alt_percent", 145: "ui_alt_plus", 146: "ui_alt_minus"})

entries = []
index = 0
for prefix, count in sections16:
    for local in range(count):
        col = index % cols16
        row = index // cols16
        name = UI_NAMES.get(local) if prefix == "ui" else None
        if name is None:
            name = f"{prefix}_{local:03d}"
        entries.append((name, col * 16, row * 16, 16, 16))
        index += 1

for local in range(40):
    col = local % cols24
    row = local // cols24
    entries.append((f"fx_{local:03d}", col * 24, part16_h + row * 24, 24, 24))

with open(out_path, "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
    f.write('<!-- tools/build_tile_atlas.sh 가 생성한다. 직접 수정하지 말 것. -->\n')
    f.write('<TextureAtlas imagePath="tiles.png">\n')
    for name, x, y, w, h in entries:
        f.write(f'\t<SubTexture name="{name}.png" x="{x}" y="{y}" width="{w}" height="{h}"/>\n')
    f.write('</TextureAtlas>\n')

print(f"서술자 {len(entries)}개 항목 -> {out_path}")
PY

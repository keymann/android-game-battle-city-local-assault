#!/usr/bin/env bash
#
# 픽셀아트 리소스를 하나의 아틀라스로 합친다.
#
#   assets/asset2                      Tiny Battle    198 x 16px  -> battle_*
#   assets/kenney_tiny-town            Tiny Town      132 x 16px  -> town_*
#   assets/kenney_tiny-dungeon         Tiny Dungeon   132 x 16px  -> dungeon_*
#   assets/kenney_desert-shooter-pack  Interface      198 x 16px  -> ui_*
#   assets/kenney_desert-shooter-pack  Weapons         40 x 24px  -> fx_*
#   assets/custom/본진_건물.png          본진 파괴 애니   4 x 32x40  -> base_*
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

BASE_W=32    # 본진 프레임. 지형(16px)보다 촘촘하지만 주인공 오브젝트라 허용한다.
BASE_H=40
BASE_COUNT=4

BATTLE_DIR="assets/asset2/Tiles"
TOWN_DIR="assets/kenney_tiny-town/Tiles"
DUNGEON_DIR="assets/kenney_tiny-dungeon/Tiles"
UI_DIR="assets/kenney_desert-shooter-pack_1.0/PNG/Interface/Tiles"
FX_DIR="assets/kenney_desert-shooter-pack_1.0/PNG/Weapons/Tiles"
BASE_SHEET="assets/custom/본진_건물.png"

list_tiles() { ls "$1"/tile_*.png | sort; }

WIDTH=$((COLS_16 * 16))

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

magick montage "${FILES_24[@]}" \
  -tile "${COLS_24}x" -geometry '24x24+0+0' -background none \
  "$TMP/part24_raw.png"

# extent 는 montage 입력이 아니라 결과에 적용해야 한다.
# 입력에 걸면 각 타일이 352px 로 늘어난 뒤 24px 로 축소돼 그림이 뭉개진다.
PART24_H=$(magick identify -format '%h' "$TMP/part24_raw.png")
magick "$TMP/part24_raw.png" -background none -gravity northwest \
  -extent "${WIDTH}x${PART24_H}" "$TMP/part24.png"

# --- 본진 구역 --------------------------------------------------------------
python3 tools/cut_base_frames.py "$BASE_SHEET" "$TMP/base" "$BASE_W" "$BASE_H"

magick montage "$TMP"/base/base_*.png \
  -tile "${BASE_COUNT}x" -geometry "${BASE_W}x${BASE_H}+0+0" -background none \
  "$TMP/partbase_raw.png"
magick "$TMP/partbase_raw.png" -background none -gravity northwest \
  -extent "${WIDTH}x${BASE_H}" "$TMP/partbase.png"

# --- 합치기 -----------------------------------------------------------------
mkdir -p "$OUT_DIR"
# 본진 스프라이트가 16bit 로 들어오면 아틀라스 전체가 16bit 이 된다.
# 런타임은 어차피 ARGB8888 로 디코드하므로 파일만 두 배가 된다. 8bit 로 고정한다.
magick "$TMP/part16.png" "$TMP/part24.png" "$TMP/partbase.png" \
  -background none -append -depth 8 -strip "$OUT_DIR/tiles.png"

PART16_H=$(magick identify -format '%h' "$TMP/part16.png")
TOTAL_W=$(magick identify -format '%w' "$OUT_DIR/tiles.png")
TOTAL_H=$(magick identify -format '%h' "$OUT_DIR/tiles.png")
echo "아틀라스 ${TOTAL_W}x${TOTAL_H} (16px ${PART16_H} / 24px ${PART24_H} / 본진 ${BASE_H})"

# --- 서술자 -----------------------------------------------------------------
python3 tools/write_atlas_xml.py \
  "$OUT_DIR/tiles.xml" "$COLS_16" "$COLS_24" "$PART16_H" "$PART24_H" \
  "$BASE_W" "$BASE_H" "$BASE_COUNT"

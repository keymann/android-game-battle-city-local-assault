#!/usr/bin/env python3
"""본진 건물 스펙 시트에서 파괴 애니메이션 프레임만 잘라낸다.

전달받은 시트(`assets/custom/본진_건물.png`)는 라벨과 화살표, 색상 변형, 방향별
버전이 함께 들어 있는 **설명용 이미지**다. 게임에 필요한 것은 위쪽 줄의
파괴 애니메이션 4프레임뿐이므로 그 부분만 오려 낸다.

프레임마다 경계 상자가 조금씩 다르다(잔해가 밖으로 튄다). 각각을 그대로 쓰면
재생 중에 건물이 흔들리므로, **가로 중앙 + 바닥선**을 맞춘 공통 캔버스에 얹은 뒤
한 번에 줄인다.

사용법: cut_base_frames.py <시트> <출력 디렉터리> <가로> <세로>
"""

import os
import subprocess
import sys

# 스펙 시트에서 실측한 각 프레임의 경계 상자 (x0, y0, x1, y1)
# 프레임 사이에 안내용 화살표(▶)가 있어 경계를 넉넉히 잡으면 같이 딸려 온다.
# 화살표를 피해 실측한 값이다.
FRAMES = [
    (375, 182, 578, 437),    # 0 정상
    (602, 189, 802, 441),    # 1 손상
    (840, 182, 1048, 446),   # 2 심한 손상 (잔해가 튄다)
    (1084, 196, 1235, 434),  # 3 폭발
]

# 시트 배경색. 완전한 검정이 아니라 약간 들뜬 회색이다.
BACKGROUND = "#0A0A0B"

# 바닥선 아래로 남겨 두는 여백. 튄 잔해가 잘리지 않도록.
GROUND_MARGIN = 15


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__)
        return 1

    sheet, out_dir, target_w, target_h = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    os.makedirs(out_dir, exist_ok=True)

    # 원본 해상도에 가깝게 8배 캔버스에서 배치한 뒤 한 번에 줄인다.
    # 미리 줄여서 합치면 프레임마다 반올림이 달라져 건물이 1px 씩 떨린다.
    cell_w, cell_h = target_w * 8, target_h * 8
    ground_y = cell_h - GROUND_MARGIN

    for index, (x0, y0, x1, y1) in enumerate(FRAMES):
        width, height = x1 - x0, y1 - y0
        piece = os.path.join(out_dir, f"piece{index}.png")
        subprocess.run(
            ["magick", sheet, "-crop", f"{width}x{height}+{x0}+{y0}", "+repage",
             "-fuzz", "10%", "-transparent", BACKGROUND, piece],
            check=True,
        )

        offset_x = (cell_w - width) // 2
        offset_y = max(0, ground_y - height)
        subprocess.run(
            ["magick", "-size", f"{cell_w}x{cell_h}", "xc:none",
             piece, "-geometry", f"+{offset_x}+{offset_y}", "-composite",
             # Box(영역 평균)는 축소에서 도트가 뭉개지지 않고 고르게 남는다.
             "-filter", "Box", "-resize", f"{target_w}x{target_h}!",
             os.path.join(out_dir, f"base_{index}.png")],
            check=True,
        )
        os.remove(piece)

    print(f"본진 프레임 {len(FRAMES)}장 -> {target_w}x{target_h}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# 비트맵 폰트

게임 에셋 팩에는 글자가 없다. HUD 는 `P1` `ENEMY 32/80` 같은 글자를 계속 써야 하므로
이 시트를 쓴다.

- 출처: Kenney **Desert Shooter Pack** (CC0)
- `font_sheet.png` — 16x16 글리프를 16칸씩 늘어놓은 시트
- `font_sheet.json` — 이름과 좌표

원래는 Kenney 팩 원본에서 잘라 썼는데, 원본 팩이 저장소에서 빠지면서 완성된 아틀라스
안에만 남아 있었다. 아틀라스를 다시 만들 때마다 옛 아틀라스가 필요한 상태는 위태로워서
여기로 옮겨 두었다. `tools/build_atlas.py` 가 이 시트를 읽는다.

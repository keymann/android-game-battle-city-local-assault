# Battle City: Local Assault — UI Asset Pack

개발 계획서의 로컬 Wi-Fi 2~4인 전차전, 3종 탱크, 4방향 모바일 조작, Phone/Tablet/Foldable 대응 요구를 바탕으로 제작한 오리지널 픽셀아트 에셋입니다.

## 구성

- `app_icon/`
  - `app_icon_master.png`: 1254×1254 원본
  - `play_store_512.png`: Play Store 등록용 512×512
  - `ic_launcher_{density}.png`: mdpi~xxxhdpi 런처 아이콘
- `splash/`
  - `splash_master_portrait.png`: 세로 원본
  - `splash_landscape_16x9.png`: 가로 16:9 중앙 크롭
- `lobby/`
  - `lobby_ui_mockup.png`: 로비 전체 화면 시안
  - `lobby_components_sheet.png`: 4×4 투명 원본 시트
  - `components/`: 버튼, 플레이어 슬롯, 네트워크/상태 배지 등 16개 개별 RGBA PNG
- `hud/`
  - `game_hud_mockup.png`: 인게임 HUD 전체 화면 시안
  - `hud_components_sheet.png`: 4×4 투명 원본 시트
  - `components/`: 조이스틱, FIRE/SPECIAL, 쿨다운, 생명, 상태/네트워크 등 16개 개별 RGBA PNG
- `previews/`: 개별 컴포넌트 검수용 모음 이미지
- `sprites/`
  - `terrain/`: 지형·본진·환경 오브젝트 48종. 반복 배치용 variation과 본진 8상태 포함
  - `tanks/`: 플레이어 3종 및 COM 3종 × 상·우·하·좌, 총 24종
  - `effects/`: 일반탄·관통탄·충돌·폭발·방어막·대시·스폰·경고 효과 32종
  - 각 폴더의 `components/`는 개별 128×128 RGBA PNG, `components_atlas_128.png`는 고정 셀 아틀라스, `components_atlas.json`은 인덱스/좌표 매핑입니다.
- `audio/`
  - `bgm/`: 메인 메뉴, 로비 대기, 일반 전투, 최종 웨이브용 루프 BGM 4곡
  - `sfx/`: 이동, 발사, 특수기, 피격, 파괴, 스폰, 경고, 승패 및 UI 효과음 19종
  - 모든 음원은 44.1kHz WAV 원본과 Android 런타임용 OGG를 함께 제공합니다.

## 적용 권장 사항

- 버튼과 상태 패널은 이미지에 글자를 합성하지 않고 Android/Game UI 레이어에서 현지화 가능한 텍스트를 올립니다.
- `primary_button_normal`/`pressed`, `fire_button_normal`/`pressed`, `special_button_normal`/`pressed`를 터치 상태별로 교체합니다.
- `cooldown_75`/`cooldown_25`는 시각 가이드입니다. 실제 쿨다운은 셰이더 또는 원형 마스크로 연속 표현하는 편이 좋습니다.
- 조이스틱 입력은 아날로그 좌표로 수집하되 계획서대로 가장 가까운 상하좌우 방향으로 양자화합니다.
- HUD 중앙은 게임 월드를 가리지 않도록 비워 두고, 컨트롤과 상태창은 기기 safe inset 기준으로 배치합니다.
- 현재 플레이어 색상 체계: P1 cyan, P2 orange, P3 lime, P4 violet.
- `*_raw.png`는 생성 원본 보관용입니다. 실제 게임에서는 배경과 셀을 정리한 `components/` 또는 `components_atlas_128.png`를 사용합니다.

# Audio resources

모든 곡과 효과음은 이 게임을 위해 절차적으로 작곡·합성한 오리지널 칩튠 오디오입니다.

## BGM

- `main_menu_theme`: 메인 메뉴, 104 BPM, 약 36.9초 루프
- `lobby_waiting_theme`: 로컬 Wi-Fi 로비 및 준비 대기, 92 BPM, 약 41.7초 루프
- `battle_theme`: 일반 전투, 132 BPM, 약 43.6초 루프
- `final_wave_theme`: 적 잔여 수가 적거나 본진 위기인 최종 웨이브, 148 BPM, 약 32.4초 루프

## SFX

- 이동/전투: `tank_move_loop`, `tank_fire`, `bullet_hit_brick`, `bullet_hit_steel`, `brick_destroy`, `tank_explosion`
- 특수기: `special_piercing`, `special_shield`, `special_dash`
- 게임 상태: `enemy_spawn`, `player_death`, `base_warning_loop`, `base_destroy`, `victory`, `game_over`
- UI/네트워크: `ui_ready`, `countdown_tick`, `countdown_go`, `network_disconnect`

## Format

- WAV: 44.1kHz, 16-bit PCM. 편집 및 원본 보관용
- OGG: Vorbis stereo. Android 런타임 패키징 권장
- `audio_manifest.json`: 길이, 루프 여부, 지원 포맷 목록

## Mixing recommendation

- BGM 기본 볼륨: 0.45~0.60
- 일반 효과음: 0.75~0.90
- 본진 경고/파괴: 0.90~1.00
- `tank_move_loop`는 이동 시작 시 재생하고 정지 시 50~100ms fade-out을 적용합니다.
- BGM 전환은 400~700ms crossfade를 권장합니다.

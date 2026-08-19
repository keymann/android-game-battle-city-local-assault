# Battle City: Local Assault

원작 Battle City의 핵심 게임 플레이를 유지하면서, **최대 4인 로컬 네트워크 멀티플레이**와 **탱크 타입/특수기 시스템**을 추가한 Android 리메이크 프로젝트입니다.

> 상세 설계는 [battle_city_android_development_plan.md](./battle_city_android_development_plan.md) 참고

---

## 주요 특징

- 최대 4인 로컬 Wi-Fi 멀티플레이 (Host Authoritative)
- 3종 플레이어 탱크 (공격형 / 방어형 / 스피드형)
- 탱크별 고유 특수기 (관통탄 / 방어막 / 대시)
- 공격력 / 방어력 / 이동속도 / 연사속도 차별화
- 플레이어별 생명 3개 (HP와 Life 분리)
- COM 탱크 타입 다양화 + 상태 머신 AI
- 모바일 터치 UI (가상 조이스틱 + Fire / Special)
- Vulkan 기반 2D 렌더링, OpenGL ES fallback
- 휴대폰 / 태블릿 / 폴더블 대응

---

## 게임 루프

```text
게임 생성 → 로컬 네트워크 방 생성 → 2~4명 참가 → 탱크 선택
    → 스테이지 시작 → COM 출현 → 전투
    → COM (20 × 플레이어 수) 전멸 → Kill Count 비교 → 최다 처치 플레이어 승리
```

---

## 원작 유지 범위

격자 기반 맵, 벽돌·강철·물·숲·얼음 타일, 본진, 4방향 이동, 포탄, 적 탱크 출현,
본진 방어, 맵 파괴, 탱크 충돌, 폭발 효과, 스테이지 클리어 구조를 유지합니다.

원작의 1인 플레이 구조만 2~4인 로컬 멀티플레이 구조로 변경합니다.

---

## 플레이어 탱크

| 능력 | 공격형 | 방어형 | 스피드형 |
|---|---:|---:|---:|
| 공격력 | 3 | 2 | 1 |
| 방어력 | 1 | 3 | 1 |
| 이동속도 | 2 | 1 | 4 |
| 연사속도 | 2 | 2 | 3 |
| 특수기 | 관통탄 | 방어막 | 대시 |
| 특수기 쿨타임 | 8초 | 10초 | 6초 |

### 특수기

- **관통탄** (공격형): 강철 블록에 높은 데미지, COM 탱크 관통
- **방어막** (방어형): 3초간 받는 피해 50% 감소
- **대시** (스피드형): 1초간 이동속도 2배, 충돌 회피용

능력치는 하드코딩하지 않고 `TankConfig` 데이터로 관리합니다.

---

## COM 탱크

COM도 공격형 / 방어형 / 스피드형 3종으로 구성되며, 타입별 AI 성향이 다릅니다.

```text
Total Enemy Count = Player Count × 20
```

| 플레이어 | 총 COM |
|---:|---:|
| 2명 | 40 |
| 3명 | 60 |
| 4명 | 80 |

**총 COM 수와 동시 존재 COM 수를 분리합니다.** (동시 존재 최대 8~12)
COM이 파괴되면 다음 COM을 생성해 저사양 기기에서 성능을 유지합니다.

### AI 상태 머신

```text
Spawn → Patrol → Chase → Attack → DefendBase → Avoid → Dead
```

본진이 위험하면 본진 방어를 우선하고, 그 외에는 가장 가까운 플레이어를 추적·공격합니다.

---

## 게임 규칙

### 데미지

```text
Damage = max(1, AttackerAttackPower - DefenderDefensePower × DefenseCoefficient)
```

### 생명

```text
HP 0 → Tank Destroyed → Life - 1 → Respawn
```

Life 3개를 모두 잃으면 해당 플레이어는 탈락합니다.

### VICTORY

모든 COM 전멸 시 플레이어별 Kill Count를 비교해 최다 처치 플레이어가 승리합니다.
동점 시 우선순위: Kill Count → 남은 Life → 남은 HP → 공동 승리

### GAME OVER

```text
본진 파괴 (아군/적군 구분 없음)
        OR
모든 플레이어 Life 소진
```

---

## 네트워크

인터넷 서버 없이 동일 Wi-Fi 네트워크 기반으로 동작합니다.
게임 생성자가 Host(권위 서버) 역할을 담당합니다.

| 방향 | 전송 데이터 |
|---|---|
| Client → Host | playerId, direction, fire, special |
| Host → Client | player / enemy / projectile / base state, score, game phase |

Host가 COM 생성·AI, 충돌 판정, 데미지, 사망, 점수, 본진 파괴, 게임 종료, 승자를 결정합니다.

### Tick

```text
Game Logic      60 Hz
Network State   20~30 Hz
Rendering       Device FPS
```

### 연결 끊김

- **Client 종료**: 해당 플레이어를 DEAD / DISCONNECTED 처리, 나머지 플레이어는 계속 진행
- **Host 종료**: 초기 버전은 Host Migration 미구현 → 게임 종료 후 로비 복귀

---

## 렌더링

```text
Vulkan 지원? ── YES ──→ Vulkan
      │
      NO ──→ OpenGL ES fallback
```

- 2D Sprite / Tile Map / Particle / Explosion / Shader / UI Rendering
- Sprite Batch, Object Pool(Bullet / Explosion / Enemy), Fixed Timestep(60 Hz)
- 논리 해상도(예: 256 × 224)를 유지하고 Viewport만 조정해 다양한 화면 비율·폴더블에 대응

---

## 아키텍처

```text
app/
├── core/        GameLoop, GameState, Time, Constants
├── gameplay/    Player, Enemy, Tank, Projectile, Base, Explosion
├── map/         Tile, TileMap, MapLoader, CollisionMap
├── ai/          EnemyAI, BehaviorState
├── network/     Host, Client, NetworkMessage, NetworkGameState
├── rendering/   VulkanRenderer, SpriteRenderer, TileRenderer, ParticleRenderer
├── input/       TouchInput, VirtualJoystick
└── ui/          LobbyScreen, GameHUD, TankSelectScreen, ResultScreen, GameOverScreen
```

### 게임 상태 머신

```text
BOOT → MAIN_MENU → CREATE / JOIN → LOBBY → TANK_SELECT → COUNTDOWN → PLAYING
    → VICTORY / GAME_OVER → RESULT → LOBBY / MENU
```

---

## 조작

- **좌측**: 가상 조이스틱 (아날로그 터치 입력 → 가장 가까운 4방향으로 변환)
- **우측**: FIRE 버튼, SPECIAL 버튼

---

## 성능 목표

| 항목 | 목표 |
|---|---:|
| 목표 FPS | 60 FPS |
| 최소 목표 FPS | 30 FPS |
| Network | 20~30 Hz |
| 동시 COM | 8~12 |
| 총 COM | 최대 80 |
| Texture | 최소화 |
| Draw Call | 최소화 |

---

## 개발 단계

| Phase | 내용 |
|---|---|
| Phase 1 | 프로젝트 기반 — Android/Kotlin, Vulkan 초기화, 게임 루프, 고정 timestep, Sprite 렌더링 |
| Phase 2 | 원작 핵심 게임 — Tile Map, 타일 종류, 본진, 탱크 이동, 포탄, 충돌, 폭발 |
| Phase 3 | 플레이어 시스템 — 3종 탱크, 능력치, HP, Life, 사망, 점수 |
| Phase 4 | 특수기 — 관통탄, 방어막, 대시, 쿨타임, UI |
| Phase 5 | COM — Spawn, 타입별 AI, State Machine, 20 × Player 수 생성 |
| Phase 6 | 로컬 멀티플레이 — Host, Join, Lobby, 상태 동기화, Disconnect 처리 |
| Phase 7 | 모바일 UI — Virtual Joystick, Fire/Special, HUD, Player Status |
| Phase 8 | 최종화 — 사운드, 이펙트, 진동, 최적화, 저사양/Tablet/Fold/해상도 테스트 |

### MVP 순서

1. **MVP 1** — 1 Player + 원작 맵 + 탱크 + 포탄 + COM + 본진
2. **MVP 2** — 3 Tank Types + HP/Life + Special Skill
3. **MVP 3** — 2 Player + LAN
4. **MVP 4** — 4 Player + 80 COM + Complete Game Rules
5. **MVP 5** — Optimization + Tablet + Fold + Low-end Android

---

## 개발 원칙

1. 게임 로직과 렌더링을 분리한다.
2. 네트워크 게임에서는 Host authoritative architecture를 사용한다.
3. Client는 게임 결과를 결정하지 않는다.
4. 모든 게임 규칙은 deterministic하게 작성한다.
5. Tank, Enemy, Projectile, Tile은 독립적인 데이터 모델을 가진다.
6. Tank 능력치는 하드코딩하지 않고 Config/Data 구조로 관리한다.
7. 게임 월드는 논리 해상도를 사용하고 실제 화면 해상도와 분리한다.
8. 4방향 이동은 논리적인 방향값으로 관리한다.
9. 모든 시간 관련 기능은 game tick 기반으로 관리한다.
10. Bullet, Enemy, Explosion은 Object Pool을 사용한다.
11. Vulkan Renderer와 Game Logic을 강하게 결합하지 않는다.
12. Android UI와 게임 렌더링 영역을 분리한다.
13. 기능 구현 후 반드시 Unit Test 또는 Debug Test를 추가한다.
14. 한 번에 대규모 코드를 생성하지 않고 Phase 단위로 구현한다.
15. 기존 구현을 수정할 때 기존 기능을 임의로 삭제하지 않는다.
16. 네트워크 입력과 게임 상태를 분리한다.
17. Host만 충돌, 데미지, 사망, 점수, 승패를 최종 결정한다.
18. 모든 네트워크 메시지는 버전 확장을 고려한 구조로 설계한다.
19. 게임 규칙과 밸런스 값은 설정 데이터로 분리한다.
20. 각 Phase가 완료될 때 빌드 및 테스트 가능한 상태를 유지한다.

---

## 사운드

원작 음원은 권리 문제가 있을 수 있으므로 오리지널 사운드 에셋을 제작합니다.

Tank Move / Tank Fire / Special / Bullet Hit / Brick Destroy / Steel Hit /
Tank Explosion / Player Death / Enemy Spawn / Base Warning / Base Destroy / Victory / Game Over

---

## 라이선스

미정

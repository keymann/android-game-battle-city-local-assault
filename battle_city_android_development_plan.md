# Battle City Android 리메이크 개발 계획서

## 1. 프로젝트 개요

### 1.1 프로젝트명

**Battle City: Local Assault**

원작 Battle City의 핵심 게임 플레이를 유지하면서 다음 요소를 추가한다.

- 최대 4인 로컬 네트워크 멀티플레이
- 3종 플레이어 탱크
- 탱크별 고유 특수기
- 탱크별 공격력/방어력/이동속도 차별화
- 플레이어별 생명 3개
- COM 탱크 타입 다양화
- 모바일 터치 UI
- Vulkan 기반 2D 렌더링
- 휴대폰/태블릿/폴더블 대응

### 1.2 핵심 게임 루프

```text
게임 생성
    ↓
로컬 네트워크 방 생성
    ↓
2~4명 플레이어 참가
    ↓
탱크 타입 선택
    ↓
스테이지 시작
    ↓
COM 탱크 출현
    ↓
플레이어 전투
    ↓
COM 20 × 플레이어 수 전멸
    ↓
플레이어별 처치 수 비교
    ↓
최다 처치 플레이어 승리
```

---

## 2. 원작 유지 범위

원작의 다음 핵심 요소는 최대한 동일하게 유지한다.

- 격자 기반 맵
- 벽돌 블록
- 강철 블록
- 물
- 숲
- 얼음
- 본진
- 탱크 이동 방식
- 4방향 이동
- 포탄
- 적 탱크 출현
- 본진 방어
- 맵 파괴
- 탱크 충돌
- 폭발 효과
- 스테이지 클리어 구조

단, 원작의 1인 플레이 구조는 2~4인 로컬 멀티플레이 구조로 변경한다.

---

## 3. 플레이어 수

| 항목 | 값 |
|---|---:|
| 최소 플레이어 | 2명 |
| 최대 플레이어 | 4명 |
| 게임 생성자 | 1명 |
| 참가자 | 최대 3명 |
| 총 플레이어 | 최대 4명 |

### 방 구조

```text
Player A
  │
  │ Game Host
  │
  ├── Player B
  ├── Player C
  └── Player D
```

게임 생성자는 Host 역할을 담당한다.

---

## 4. 로컬 네트워크

### 4.1 네트워크 방식

인터넷 서버를 사용하지 않고 동일 Wi-Fi 네트워크 기반으로 구성한다.

```text
             Wi-Fi
               │
        ┌──────┴──────┐
        │             │
      Host          Client
        │             │
        ├──── Client ─┤
        │             │
        └──── Client ─┘
```

### 4.2 Authority 구조

Host가 게임 상태의 권위 서버 역할을 담당한다.

```text
                HOST
        ┌────────┼────────┐
        │        │        │
      Player   Player   Player
        A        B        C
                 │
              Player D
```

Host가 결정하는 항목:

- COM 생성
- COM AI
- 충돌 판정
- 탄환 충돌
- 데미지
- 사망
- 점수
- 본진 파괴
- 게임 종료
- 승자 결정

Client는 주로 다음 입력을 Host에 전달한다.

```text
INPUT
 ├─ Move Direction
 ├─ Fire
 └─ Special
```

---

## 5. 게임 상태 모델

게임 상태는 명확하게 분리한다.

```text
GameState
 ├─ phase
 ├─ stage
 ├─ players
 ├─ enemies
 ├─ projectiles
 ├─ base
 ├─ score
 ├─ remainingEnemies
 └─ winner
```

### 5.1 PlayerState

```text
PlayerState
 ├─ playerId
 ├─ tankType
 ├─ position
 ├─ direction
 ├─ hp
 ├─ lives
 ├─ score
 ├─ specialCooldown
 └─ alive
```

### 5.2 EnemyState

```text
EnemyState
 ├─ enemyId
 ├─ enemyType
 ├─ position
 ├─ direction
 ├─ hp
 ├─ attackPower
 ├─ speed
 └─ state
```

---

## 6. 플레이어 탱크 종류

플레이어는 게임 시작 전에 3가지 중 하나를 선택한다.

- 공격형
- 방어형
- 스피드형

### 6.1 공격형

강력한 포탄을 사용하는 공격 특화 탱크.

| 능력 | 값 |
|---|---:|
| 공격력 | 3 |
| 방어력 | 1 |
| 이동속도 | 2 |
| 연사속도 | 2 |
| 특수기 | 관통탄 |
| 특수기 쿨타임 | 8초 |

#### 특수기: 관통탄

일반 포탄보다 강력한 포탄을 발사한다.

- 강철 블록에 높은 데미지
- COM 탱크 관통
- 재사용 대기시간 8초

---

### 6.2 방어형

느리지만 높은 생존력을 가진 탱크.

| 능력 | 값 |
|---|---:|
| 공격력 | 2 |
| 방어력 | 3 |
| 이동속도 | 1 |
| 연사속도 | 2 |
| 특수기 | 방어막 |
| 특수기 쿨타임 | 10초 |

#### 특수기: 방어막

일정 시간 동안 받는 데미지를 크게 감소시킨다.

- 지속시간 3초
- 받는 피해 50% 감소
- 쿨타임 10초

---

### 6.3 스피드형

빠른 이동과 높은 회피능력을 가진 탱크.

| 능력 | 값 |
|---|---:|
| 공격력 | 1 |
| 방어력 | 1 |
| 이동속도 | 4 |
| 연사속도 | 3 |
| 특수기 | 대시 |
| 특수기 쿨타임 | 6초 |

#### 특수기: 대시

짧은 시간 동안 매우 빠르게 이동한다.

- 지속시간 1초
- 이동속도 2배
- 충돌 회피 용도로 사용
- 쿨타임 6초

---

## 7. 탱크 밸런스

초기 밸런스는 다음 값으로 시작한다.

| 능력 | 공격형 | 방어형 | 스피드형 |
|---|---:|---:|---:|
| 공격력 | 3 | 2 | 1 |
| 방어력 | 1 | 3 | 1 |
| 이동속도 | 2 | 1 | 4 |
| 연사속도 | 2 | 2 | 3 |
| 특수기 | 관통탄 | 방어막 | 대시 |
| 특수기 쿨타임 | 8초 | 10초 | 6초 |

### 중요 구현 원칙

능력치를 코드에 하드코딩하지 않는다.

```text
TankConfig
 ├─ attackPower
 ├─ defensePower
 ├─ moveSpeed
 ├─ fireCooldown
 ├─ specialCooldown
 └─ specialDuration
```

향후 밸런스 조정은 설정값만 변경할 수 있도록 한다.

---

## 8. COM 탱크

COM에도 3가지 타입의 능력치를 부여한다.

- 공격형
- 방어형
- 스피드형

### 8.1 공격형 COM

```text
Attack = 3
Defense = 1
Speed = 2
```

공격적인 AI를 사용한다.

### 8.2 방어형 COM

```text
Attack = 2
Defense = 3
Speed = 1
```

본진 주변을 방어하는 AI를 우선한다.

### 8.3 스피드형 COM

```text
Attack = 1
Defense = 1
Speed = 4
```

플레이어를 적극적으로 추적한다.

---

## 9. COM 생성 규칙

총 COM 수는 다음 공식으로 계산한다.

```text
Total Enemy Count = Player Count × 20
```

| 플레이어 | 총 COM |
|---:|---:|
| 2명 | 40 |
| 3명 | 60 |
| 4명 | 80 |

### 중요

총 COM 수와 동시에 존재하는 COM 수를 분리한다.

예:

```text
총 COM = 80
동시 존재 최대 = 8~12
```

COM이 파괴되면 다음 COM을 생성한다.

이를 통해 저사양 Android 기기에서 성능을 유지한다.

---

## 10. COM AI

초기 AI는 상태 머신 방식으로 구현한다.

```text
EnemyAI
 ├─ Spawn
 ├─ Patrol
 ├─ Chase
 ├─ Attack
 ├─ DefendBase
 ├─ Avoid
 └─ Dead
```

### 기본 AI 우선순위

```text
본진 위험?
   ↓ Yes
본진 방어

No
 ↓
가장 가까운 플레이어 탐색
 ↓
플레이어 추적
 ↓
공격
```

---

## 11. COM 생성 위치

원작과 유사하게 맵의 지정된 Spawn Point에서 생성한다.

```text
SpawnPoint
 ├─ Top Left
 ├─ Top Center
 └─ Top Right
```

COM 생성 시 다음 조건을 검사한다.

- 플레이어와 너무 가까운가?
- 다른 COM과 겹치는가?
- 장애물 내부인가?
- 본진과 겹치는가?

조건을 만족하지 않으면 다른 Spawn Point를 선택한다.

---

## 12. 플레이어 생명 시스템

각 플레이어는 기본적으로 하트 3개를 가진다.

```text
❤️ ❤️ ❤️
```

HP와 Life를 분리한다.

```text
Life = 3
HP = 100
```

피격 시 HP가 감소한다.

HP가 0이 되면:

```text
HP 0
 ↓
Tank Destroyed
 ↓
Life - 1
 ↓
Respawn
```

3개의 생명을 모두 잃으면 해당 플레이어는 탈락한다.

---

## 13. 공격력과 데미지

피해량은 공격하는 탱크의 공격력과 방어하는 탱크의 방어력을 기반으로 계산한다.

초기 공식:

```text
Damage =
AttackerAttackPower
-
DefenderDefensePower × DefenseCoefficient
```

최소 데미지는 1로 제한한다.

```text
Damage = max(1, CalculatedDamage)
```

예:

```text
공격형 → 스피드형

Attack = 3
Defense = 1

Damage ≈ 2
```

```text
방어형 → 방어형

Attack = 2
Defense = 3

Damage ≈ 1
```

실제 밸런싱 과정에서 정수 단위로 조정한다.

---

## 14. 본진

본진은 게임의 핵심 보호 대상이다.

```text
      Enemy
        ↓

   ┌─────────┐
   │  BASE   │
   │    🦅   │
   └─────────┘
```

### BaseState

```text
BaseState
 ├─ position
 ├─ destroyed
 └─ protected
```

---

## 15. 본진 파괴 규칙

다음 중 하나라도 발생하면 즉시 GAME OVER.

### 15.1 COM이 본진 파괴

```text
Enemy Bullet
     ↓
   BASE
     ↓
GAME OVER
```

### 15.2 플레이어가 본진 파괴

아군 탱크의 포탄이 본진을 공격해서 파괴해도 즉시 GAME OVER.

```text
Player Bullet
     ↓
   BASE
     ↓
GAME OVER
```

본진은 아군/적군 구분 없이 공격으로 파괴될 수 있다.

---

## 16. 승리 조건

### 16.1 모든 COM 전멸

```text
Remaining Enemy = 0
```

그 순간 플레이어별 처치 수를 비교한다.

예:

```text
Player A = 18
Player B = 25
Player C = 12
Player D = 25
```

가장 많이 처치한 플레이어가 승리한다.

### 16.2 동점 처리

초기 버전에서는 다음 순서로 우선순위를 정한다.

1. Kill Count
2. 남은 Life
3. 남은 HP
4. 공동 승리

---

## 17. GAME OVER 조건

다음 조건 중 하나라도 발생하면 GAME OVER.

```text
Base Destroyed
        OR
All Players Dead
```

정리:

```text
COM 전멸
 └─ Victory

본진 파괴
 └─ GAME OVER

모든 플레이어 생명 소진
 └─ GAME OVER
```

---

## 18. 모바일 조작 UI

일반적인 모바일 게임 인터페이스를 사용한다.

### 18.1 왼쪽

가상 조이스틱:

```text
       ↑
    ←  ●  →
       ↓
```

4방향 이동을 기본으로 한다.

### 18.2 오른쪽

```text
       SPECIAL
          ▲

      FIRE ●
```

- FIRE: 포탄 발사
- SPECIAL: 특수기

---

## 19. 모바일 입력

조이스틱은 아날로그 터치 입력으로 받되 실제 탱크 이동은 4방향으로 제한한다.

```text
Touch Position
      ↓
Vector
      ↓
가장 가까운 방향 계산
      ↓
UP / DOWN / LEFT / RIGHT
```

이를 통해 원작의 조작감을 유지한다.

---

## 20. 화면 구성

### 전투 화면

```text
┌──────────────────────────────┐
│ P1 ❤️❤️❤️    SCORE 120       │
│ P2 ❤️❤️❤️    ENEMY 32/80     │
│                              │
│        GAME MAP              │
│                              │
│                              │
│                              │
│                              │
│ ◉                            │
│ JOYSTICK             SPECIAL │
│                      ● FIRE   │
└──────────────────────────────┘
```

4인 게임에서는 상단에 플레이어 상태를 표시한다.

```text
P1 ❤️❤️❤️  20
P2 ❤️❤️♡   15
P3 ❤️♡♡    8
P4 💀      12
```

---

## 21. 화면 비율 대응

지원 대상:

- 일반 Android Phone
- 대화면 Phone
- Tablet
- Foldable
- Landscape
- 다양한 Aspect Ratio

게임 월드는 고정된 논리 해상도를 사용한다.

예:

```text
Logical Resolution
256 × 224
```

실제 화면에서는 다음 구조를 사용한다.

```text
Logical Game World
       ↓
Viewport
       ↓
Device Screen
```

게임 월드 자체의 타일 크기는 유지하고 viewport를 조정한다.

---

## 22. Foldable 대응

폴더블에서는 화면 상태에 따라 게임 viewport를 자동 확장한다.

```text
Folded
┌──────────┐
│ GAME     │
│          │
└──────────┘

Unfolded
┌──────────────────────┐
│                      │
│        GAME          │
│                      │
└──────────────────────┘
```

게임 월드의 논리 좌표계는 유지하고 viewport만 변경한다.

---

## 23. 그래픽 엔진

기본 렌더러는 Vulkan 기반으로 구현한다.

```text
Android
   │
   ├─ Kotlin / Java
   │
   └─ Game Engine
        │
        ├─ Game Logic
        ├─ Physics
        ├─ Network
        ├─ Input
        │
        └─ Renderer
              │
           Vulkan
```

### 렌더링 목표

- 2D Sprite
- Tile Map
- Particle
- Explosion
- Shader
- UI Rendering

---

## 24. 저사양 기기 대응

Vulkan을 기본 렌더러로 사용하되 Vulkan 미지원 또는 호환성이 낮은 기기를 위해 OpenGL ES fallback을 제공한다.

```text
Renderer
   │
   ├─ Vulkan
   │
   └─ OpenGL ES fallback
```

실행 시:

```text
Vulkan 지원?
   │
  YES ──→ Vulkan
   │
   NO
   ↓
OpenGL ES
```

---

## 25. 렌더링 최적화

### 25.1 Sprite Batch

가능하면 여러 Sprite를 하나의 Batch로 처리한다.

### 25.2 Object Pool

다음 객체는 Object Pool을 사용한다.

```text
Bullet Pool
Explosion Pool
Enemy Pool
```

### 25.3 Fixed Timestep

게임 물리와 로직은 고정 timestep을 사용한다.

```text
Game Logic = 60 Hz
```

렌더링 FPS와 게임 로직을 분리한다.

---

## 26. 게임 아키텍처

권장 프로젝트 구조:

```text
app/
├── core/
│   ├── GameLoop
│   ├── GameState
│   ├── Time
│   └── Constants
│
├── gameplay/
│   ├── Player
│   ├── Enemy
│   ├── Tank
│   ├── Projectile
│   ├── Base
│   └── Explosion
│
├── map/
│   ├── Tile
│   ├── TileMap
│   ├── MapLoader
│   └── CollisionMap
│
├── ai/
│   ├── EnemyAI
│   └── BehaviorState
│
├── network/
│   ├── Host
│   ├── Client
│   ├── NetworkMessage
│   └── NetworkGameState
│
├── rendering/
│   ├── VulkanRenderer
│   ├── SpriteRenderer
│   ├── TileRenderer
│   └── ParticleRenderer
│
├── input/
│   ├── TouchInput
│   └── VirtualJoystick
│
└── ui/
    ├── LobbyScreen
    ├── GameHUD
    ├── TankSelectScreen
    ├── ResultScreen
    └── GameOverScreen
```

---

## 27. 게임 상태 머신

```text
BOOT
 ↓
MAIN_MENU
 ↓
CREATE / JOIN
 ↓
LOBBY
 ↓
TANK_SELECT
 ↓
COUNTDOWN
 ↓
PLAYING
 ↓
 ┌───────────────┐
 │               │
VICTORY       GAME_OVER
 │               │
 └───────┬───────┘
         ↓
     RESULT
         ↓
     LOBBY / MENU
```

---

## 28. Lobby

### Host

```text
CREATE GAME
```

### Client

```text
JOIN GAME
```

### Lobby 화면

```text
┌─────────────────────────┐
│      BATTLE CITY        │
│                         │
│ Player 1   READY        │
│ Player 2   READY        │
│ Player 3   WAITING      │
│ Player 4   WAITING      │
│                         │
│       START GAME        │
└─────────────────────────┘
```

Host만 START GAME을 누를 수 있도록 한다.

최소 인원은 2명이다.

---

## 29. 탱크 선택 화면

```text
SELECT YOUR TANK

┌─────────┐
│ ATTACK  │
│   TANK  │
│ ATK ★★★ │
│ DEF ★   │
│ SPD ★★  │
└─────────┘

┌─────────┐
│ DEFENSE │
│   TANK  │
│ ATK ★★  │
│ DEF ★★★ │
│ SPD ★   │
└─────────┘

┌─────────┐
│  SPEED  │
│   TANK  │
│ ATK ★   │
│ DEF ★   │
│ SPD ★★★ │
└─────────┘
```

---

## 30. 맵 시스템

원작 스타일의 타일 기반 구조를 유지한다.

### TileType

```text
EMPTY
BRICK
STEEL
WATER
FOREST
ICE
BASE
SPAWN
```

### Tile

```text
Tile
 ├─ type
 ├─ destructible
 ├─ collision
 ├─ movementModifier
 └─ renderLayer
```

맵을 이미지로만 관리하지 않고 데이터 기반 Tile Map으로 구현한다.

---

## 31. 충돌 시스템

충돌 우선순위:

```text
Tank
 ↓
Tile Collision
 ↓
Tank Collision
 ↓
Projectile Collision
 ↓
Base Collision
```

포탄은 다음과 충돌할 수 있다.

- 벽돌
- 강철
- 탱크
- 본진
- 맵 경계

---

## 32. 점수

플레이어별 처치 수를 기록한다.

```text
PlayerScore
 ├─ enemyKills
 ├─ damage
 └─ deaths
```

초기 버전에서는:

```text
COM 1대 = 1 Kill
```

모든 COM 타입의 점수는 동일하게 한다.

향후 필요하면 타입별 가중치를 추가할 수 있도록 구조를 확장한다.

---

## 33. 게임 결과 화면

```text
┌────────────────────────────┐
│         VICTORY            │
│                            │
│       PLAYER 2             │
│                            │
│ P1   18 KILLS              │
│ P2   27 KILLS   ★ WINNER  │
│ P3   15 KILLS              │
│ P4   20 KILLS              │
│                            │
│      PLAY AGAIN            │
└────────────────────────────┘
```

### GAME OVER

```text
┌────────────────────────────┐
│         GAME OVER          │
│                            │
│       BASE DESTROYED       │
│                            │
│       PLAY AGAIN            │
│       MAIN MENU             │
└────────────────────────────┘
```

---

## 34. 사운드

원작의 분위기를 유지하되 원작 음원을 그대로 사용하는 것은 별도의 권리 문제가 있을 수 있으므로 오리지널 사운드 에셋을 제작한다.

필요한 효과음:

```text
Tank Move
Tank Fire
Special
Bullet Hit
Brick Destroy
Steel Hit
Tank Explosion
Player Death
Enemy Spawn
Base Warning
Base Destroy
Victory
Game Over
```

---

## 35. 네트워크 동기화 전략

### Client → Host

```text
INPUT
 ├─ playerId
 ├─ direction
 ├─ fire
 └─ special
```

### Host → Client

```text
GAME_STATE
 ├─ player states
 ├─ enemy states
 ├─ projectile states
 ├─ base state
 ├─ score
 └─ game phase
```

Host가 게임의 최종 결과를 결정한다.

---

## 36. 네트워크 Tick

권장 초기값:

```text
Game Logic      60 Hz
Network State   20~30 Hz
Rendering       Device FPS
```

예:

```text
60 FPS Game
 ↓
Network 20 FPS
 ↓
Client interpolation
```

게임 특성상 20~30Hz 정도의 상태 동기화로 충분하다.

---

## 37. 연결 끊김 처리

### Client 연결 종료

```text
Client disconnected
        ↓
Host detects
        ↓
Player DEAD / DISCONNECTED
        ↓
Remaining players continue
```

### Host 연결 종료

초기 버전에서는 Host Migration을 구현하지 않는다.

```text
HOST DISCONNECTED
        ↓
GAME END
        ↓
RETURN TO LOBBY
```

향후 필요하면 Host Migration을 별도 기능으로 추가한다.

---

## 38. 게임 시작 카운트다운

모든 플레이어가 준비되면:

```text
3
2
1
GO!
```

Host의 게임 시작 timestamp를 기준으로 모든 Client가 게임을 시작한다.

---

## 39. 성능 목표

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

## 40. 개발 단계

AI 바이브 코딩에서는 한 번에 전체 게임을 구현하지 않고 Phase 단위로 개발한다.

### Phase 1 — 프로젝트 기반

- [ ] Android 프로젝트 생성
- [ ] Kotlin 기반 구성
- [ ] Vulkan 초기화
- [ ] 게임 루프
- [ ] 고정 timestep
- [ ] 기본 Sprite 렌더링
- [ ] 화면 크기 대응

### Phase 2 — 원작 핵심 게임

- [ ] Tile Map
- [ ] 벽돌
- [ ] 강철
- [ ] 물
- [ ] 숲
- [ ] 본진
- [ ] 탱크 이동
- [ ] 포탄
- [ ] 충돌
- [ ] 폭발

### Phase 3 — 플레이어 시스템

- [ ] 3종 탱크
- [ ] 능력치
- [ ] HP
- [ ] 하트 3개
- [ ] 사망
- [ ] 점수

### Phase 4 — 특수기

- [ ] 공격형 관통탄
- [ ] 방어형 방어막
- [ ] 스피드형 대시
- [ ] 쿨타임
- [ ] UI

### Phase 5 — COM

- [ ] COM Spawn
- [ ] Attack AI
- [ ] Defense AI
- [ ] Speed AI
- [ ] AI State Machine
- [ ] 20 × Player 수 생성

### Phase 6 — 로컬 멀티플레이

- [ ] Host
- [ ] Join
- [ ] Lobby
- [ ] Player Sync
- [ ] Projectile Sync
- [ ] Enemy Sync
- [ ] Score Sync
- [ ] Disconnect 처리

### Phase 7 — 모바일 UI

- [ ] Virtual Joystick
- [ ] Fire Button
- [ ] Special Button
- [ ] HUD
- [ ] Player Status
- [ ] Score
- [ ] Remaining Enemy

### Phase 8 — 최종화

- [ ] 사운드
- [ ] 이펙트
- [ ] 진동
- [ ] 성능 최적화
- [ ] 저사양 테스트
- [ ] Tablet 테스트
- [ ] Fold 테스트
- [ ] 다양한 해상도 테스트

---

## 41. AI 바이브 코딩용 개발 원칙

프로젝트 시작 시 AI에게 다음 원칙을 전달한다.

```text
1. 게임 로직과 렌더링을 분리한다.

2. 네트워크 게임에서는 Host authoritative architecture를 사용한다.

3. Client는 게임 결과를 결정하지 않는다.

4. 모든 게임 규칙은 deterministic하게 작성한다.

5. Tank, Enemy, Projectile, Tile은 독립적인 데이터 모델을 가진다.

6. Tank 능력치는 코드에 하드코딩하지 않고 Config/Data 구조로 관리한다.

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
```

---

## 42. 권장 MVP 순서

### MVP 1

```text
1 Player
+
원작 맵
+
탱크
+
포탄
+
COM
+
본진
```

### MVP 2

```text
3 Tank Types
+
HP/Life
+
Special Skill
```

### MVP 3

```text
2 Player
+
LAN
```

### MVP 4

```text
4 Player
+
80 COM
+
Complete Game Rules
```

### MVP 5

```text
Optimization
+
Tablet
+
Fold
+
Low-end Android
```

---

## 43. 최종 게임 규칙

```text
[PLAYER]

최소 2명
최대 4명

탱크 타입:
- 공격형
- 방어형
- 스피드형

각 탱크:
- 공격력
- 방어력
- 이동속도
- 연사속도
- 특수기 1개

Life:
❤️ ❤️ ❤️


[COM]

총 COM 수
= Player 수 × 20

2 Player → 40
3 Player → 60
4 Player → 80

COM 타입:
- 공격형
- 방어형
- 스피드형


[GAME OVER]

본진 파괴
OR
모든 플레이어 Life 소진


[VICTORY]

모든 COM 전멸

→ Player별 Kill Count 비교
→ 가장 많이 처치한 Player 승리


[CONTROL]

LEFT  = Virtual Joystick
RIGHT = Fire
RIGHT = Special


[NETWORK]

Local Wi-Fi
Host + Client
2~4 Players
Host Authoritative


[GRAPHICS]

Primary  = Vulkan
Fallback = OpenGL ES

Target:
Phone
Tablet
Foldable
Low-end Android
```

---

## 44. 추가 권장 사항

### 44.1 Friendly Fire

플레이어 간 공격은 허용할 수 있도록 설계하되, 플레이어 공격으로 본진이 파괴되면 즉시 GAME OVER가 발생하도록 한다.

### 44.2 COM 총량과 동시 출현 수 분리

4인 게임에서 총 80개의 COM을 한꺼번에 생성하지 않는다.

```text
Total Enemy = 80
Max Active Enemy = 8~12
```

### 44.3 맵은 데이터 기반으로 관리

맵을 단순 이미지로 구현하지 않고 다음과 같은 Tile 데이터로 관리한다.

```text
BRICK
STEEL
WATER
FOREST
ICE
BASE
SPAWN
```

이를 통해 향후 맵 에디터, 새로운 스테이지, 랜덤 맵 생성 등으로 확장할 수 있도록 한다.

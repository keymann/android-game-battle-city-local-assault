# 랜덤 스테이지 생성 설계 (Procedural Stage Generation)

> 요구사항: **게임 스테이지를 구성할 때마다 맵이 랜덤으로 만들어지고, 선별된 리소스와 환경 오브젝트를 최대한 활용한다.**
> 제약: 계획서 §41-4 **모든 게임 규칙은 deterministic**, §41-16 네트워크 입력과 게임 상태 분리, §44.3 데이터 기반 Tile 관리

---

## 1. 네트워크 관점 — 맵은 전송하지 않고 "seed"만 전송한다

```text
HOST                                   CLIENT × 3
  │                                        │
  │  StageSeedMessage {                    │
  │    seed: Long                          │
  │    playerCount: Int                    │
  │    stageIndex: Int                     │
  │    generatorVersion: Int   ────────────▶  동일 알고리즘으로 로컬 생성
  │  }                                     │
  │                                        │
  │  ◀──────── StageReadyMessage(gridHash) ┤
  │                                        │
  │  gridHash 불일치 →  FullGridMessage 폴백 전송
```

- 26×26~38×38 그리드를 매 스테이지 전송하지 않고 **8바이트 seed**만 보낸다
- 생성기는 순수 함수다: `generate(seed, playerCount, stageIndex, version) → StageData`
- 클라이언트가 산출한 `gridHash`(FNV-1a)를 Host가 대조한다. 버전 스큐 등으로 불일치하면 Host가 전체 그리드를 폴백 전송하고, 이후에도 **최종 판정 권한은 Host에만 있다**(§41-17)
- RNG는 플랫폼 독립적인 **xorshift128+** 를 직접 구현한다. `java.util.Random`/`kotlin.random.Random`은 구현 변경 리스크가 있어 쓰지 않는다

---

## 2. 맵 규격 — 가로:세로 **4:3** 고정

게임이 항상 landscape 로 돌기 때문에 정사각형 맵은 좌우를 크게 낭비한다.
논리 해상도를 4:3 으로 고정한다. (계획서 §21)

```text
BLOCKS_X = 16 + 4 × (playerCount − 2)
BLOCKS_Y = BLOCKS_X × 3 / 4
```

| 플레이어 | 블록 | 셀 | 논리 px | 비율 |
|---:|---:|---:|---:|---:|
| 2명 | 16 × 12 | 32 × 24 | 1024 × 768 | 4:3 |
| 3명 | 20 × 15 | 40 × 30 | 1280 × 960 | 4:3 |
| 4명 | 24 × 18 | 48 × 36 | 1536 × 1152 | 4:3 |

세로 12~18 블록은 원작(13×13)과 비슷한 종심을 유지하고, 늘어난 인원만큼 가로로 넓힌다.
동시 COM 8~12기(§44.2)와 플레이어 4기가 서로를 찾을 수 있는 밀도를 유지한다.

화면이 4:3 보다 넓으면 [Viewport] 가 좌우를 레터박스로 남긴다. 그 여백이 Phase 7 의
HUD 자리가 된다. (계획서 §20)

---

## 3. 생성 파이프라인

```text
seed
 │
 ├─ [0] RNG 초기화 (xorshift128+)
 │
 ├─ [1] Theme Roll        ── 바이옴 / 도로 양식 / 해저드 비중 / 프롭 팔레트
 │
 ├─ [2] Ground Layer      ── 지형 + 도로망 (충돌 없음, asset1 40타일 오토타일)
 │
 ├─ [3] Structure Layer   ── BRICK / STEEL 패턴 스탬프 + 좌우 미러
 │
 ├─ [4] Hazard Layer      ── WATER 연못 / ICE 패치 / FOREST 군락
 │
 ├─ [5] Anchor Layer      ── BASE + 방벽, COM 스폰 3, 플레이어 스폰 N
 │
 ├─ [6] Prop Layer        ── 환경 오브젝트 (폭발물 / 엄폐물 / 장식)
 │
 ├─ [7] Validate & Repair ── 연결성·스폰 안전·본진 접근로
 │
 └─ StageData
```

---

### [1] Theme Roll

매 스테이지 완전히 다른 인상을 만드는 단계.

| 항목 | 값 | 확률 |
|---|---|---|
| `biome` | `GRASS` / `SAND` / `MIXED`(대각 분할) | 45 / 35 / 20 |
| `roadStyle` | `NONE` / `CROSS` / `RING` / `GRID` | 20 / 30 / 25 / 25 |
| `structureDensity` | 0.18 ~ 0.34 | 균등 |
| `hazardMix` | WATER·ICE·FOREST 가중치 3개를 디리클레 유사 분배 | — |
| `propPalette` | 11개 프롭 그룹 중 **3~5개 셔플 선택** | — |
| `symmetry` | `MIRROR_X` (기본) / `NONE`(20%) | 80 / 20 |

`propPalette`가 매번 달라지므로 "드럼통 밭 스테이지", "모래주머니 참호전 스테이지", "숲+철제 상자 스테이지"처럼 스테이지 성격이 갈린다.

---

### [2] Ground Layer — 도로망 오토타일

충돌에 영향을 주지 않는 순수 배경. asset1 지형 40종을 전부 쓰기 위한 레이어다.

1. `roadStyle`에 따라 블록 그래프 위에 도로 경로를 그린다
   - `CROSS` — 중앙 십자
   - `RING` — 외곽 순환로 + 중앙 진입로
   - `GRID` — 3~4칸 간격 격자, 일부 구간 랜덤 삭제
2. 각 도로 블록의 4방향 이웃 비트마스크(N/E/S/W)로 타일을 고른다

   | 이웃 | 타일 |
   |---|---|
   | N+S | `roadNorth` |
   | E+W | `roadEast` |
   | N+E | `roadCornerUR` … (4종) |
   | 3방향 | `roadSplitN/E/S/W` |
   | 4방향 | `roadCrossing` / `roadCrossingRound` (25% 확률로 라운드) |

3. `biome = MIXED`면 잔디/모래 경계에 `tileGrass_transitionN/E/S/W`, 도로가 경계를 넘으면 `tileGrass_roadTransition*` / `*_dirt` 를 배치한다
4. 도로가 아닌 블록은 `tileGrass1/2`, `tileSand1/2` 중 랜덤 (2종 섞어 반복감 제거)

> 도로는 지형 판정에 영향을 주지 않지만, **탱크가 어디로 다닐지에 대한 시각적 유도**로 작동한다.

---

### [3] Structure Layer — 패턴 스탬프

완전 무작위 배치는 원작 느낌이 나지 않으므로, 원작 스테이지에서 반복되는 형태를 **5×5 cell 조각**으로 정의하고 격자에 찍는다.

```text
WALL_H     BOX        PILLARS    MAZE       CHECKER    STEEL_CORE
.....      #####      #.#.#      ####.      #.#.#      .....
#####      #...#      .....      ....#      .#.#.      .SSS.
#####      #...#      #.#.#      .####      #.#.#      .S.S.
.....      #...#      .....      #....      .#.#.      .SSS.
.....      #####      #.#.#      .####      #.#.#      .....
```

1. 맵을 5×5 cell 셀타일로 나눈다
2. 각 셀타일에 `structureDensity` 확률로 패턴 하나를 랜덤 회전/반전하여 스탬프
3. 스탬프의 `#`는 기본 `BRICK`, 10~20% 확률로 `STEEL`로 승격 (패턴 `STEEL_CORE`는 전부 `STEEL`)
4. `symmetry = MIRROR_X`면 좌측 절반만 생성하고 우측에 미러링 → 좌우 플레이어 간 공정성 확보 (본진이 하단 중앙이므로 상하 미러는 쓰지 않는다)

---

### [4] Hazard Layer

| 해저드 | 생성 방식 | 개수 |
|---|---|---|
| `WATER` | 3×3~5×5 블록 영역에 셀룰러 오토마타 2세대 → 유기적 연못 | 1~3 |
| `ICE` | 축정렬 사각 패치 (2×2~4×4 블록) | 0~2 |
| `FOREST` | 포아송 디스크 샘플링으로 나무 군락 | 2~5 |

규칙:
- 연못이 맵을 좌우로 완전히 갈라 통행을 끊으면 중앙 1블록을 다리(`#148`/`#130`)로 뚫는다
- `WATER`/`ICE`는 `BASE`·스폰 반경 3블록 안에 생성하지 않는다
- `FOREST`는 은폐만 하고 통행을 막지 않으므로 어디에나 배치 가능 — 단, 본진 위 1블록은 제외(본진이 안 보이면 안 됨)

---

### [5] Anchor Layer — 본진과 스폰

원작 배치를 그대로 따른다.

```text
        ▲ COM SPAWN (상단 3지점)
   ┌────┬────────────┬────┐
   │ SL │     SC     │ SR │        SL/SC/SR = 좌 / 중앙 / 우
   │    │            │    │        ±2블록 랜덤 오프셋
   │                      │
   │      (전장)          │
   │                      │
   │  P3   ┌──┐   P4      │        본진 방벽: ㄷ자 BRICK
   │       │BB│           │        (원작과 동일, 8 cell)
   │  P1 ──┴──┴── P2      │
   └──────────────────────┘
```

- `BASE` — 하단 중앙 블록 고정. 좌우/상단을 감싸는 **ㄷ자 BRICK 방벽** 8 cell
- `COM SPAWN` — 상단 3지점. 각각 ±2블록 범위에서 랜덤 오프셋, 단 서로 4블록 이상 이격
- `PLAYER SPAWN` — 본진 좌우로 `playerCount`개 대칭 배치
- **모든 스폰 지점의 반경 2블록은 강제로 `EMPTY`** 로 밀어낸다 (계획서 §11 스폰 조건 검사의 사전 보장)

---

### [6] Prop Layer — 환경 오브젝트

`propPalette`에서 뽑힌 3~5개 그룹만 사용한다.

| 종류 | 배치 규칙 | 밀도 |
|---|---|---|
| `explosive` (`fuelDepot`, `ordnance`) | 2~4개씩 **군집** 배치. 파괴 시 연쇄 폭발로 주변 `BRICK`까지 날림 | 맵당 3~8 군집 |
| `solid` (`supplyCrate`, `armorCrate`) | 교차로·개활지에 단독 배치 → 엄폐물 | 빈 블록의 4~8% |
| `solid + layout:line` (`sandbagLine`, `roadblock`) | 3~6칸 직선/L자 → 참호·바리케이드 | 맵당 2~5줄 |
| `decor` (나머지 5그룹) | 충돌 없음. 도로 위엔 `oldTracks`/`scorch`, 잔디엔 `foliage`/`cabling` 우선 | 빈 블록의 12~20% |

제약:
- 스폰 반경 2블록, 본진 방벽 인접 1블록에는 `explosive`를 두지 않는다 (시작하자마자 본진이 날아가는 사고 방지)
- `solid` 프롭은 통행 검증([7]) 대상에 포함된다. `decor`는 제외
- 프롭도 `Tile` 데이터로 관리한다. 이미지 배치가 아니다 (§44.3)

---

### [7] Validate & Repair

```text
1) Flood fill (통행 가능 = EMPTY / FOREST / ICE / SPAWN)
2) 검사:
     모든 PLAYER SPAWN  → BASE 도달 가능?
     모든 COM SPAWN     → BASE 도달 가능?
     모든 PLAYER SPAWN  ↔ 모든 COM SPAWN 도달 가능?
3) 실패 → A* 로 최단 경로를 구해 경로상의 BRICK / STEEL / solid prop 을 EMPTY 로 치환
          (repair 최대 3회)
4) 그래도 실패 → seed = seed × 6364136223846793005 + 1442695040888963407 로 재생성
                 (최대 8회, 초과 시 폴백 정적 맵 사용)
```

- COM이 본진에 못 가면 게임이 안 끝나고, 플레이어가 COM에 못 가면 이길 수 없다. 두 방향 모두 검사한다
- 재생성 시에도 seed 갱신이 결정론적이므로 Host/Client 결과가 일치한다

---

## 4. 출력 자료구조

```text
StageData
 ├─ blocksX / blocksY : Int   맵 블록 수 (항상 4:3)
 ├─ cells  : ByteArray    cellsX × cellsY TileType — 충돌·판정의 유일한 근거
 ├─ cellSprite : ShortArray   셀별 오브젝트 스프라이트 인덱스 (렌더 전용)
 ├─ ground : ShortArray   블록별 지형 스프라이트 인덱스 (렌더 전용)
 ├─ decor  : List<DecorInstance>  { spriteId, x, y, rotation, layer }
 ├─ props  : List<PropInstance>   { groupId, spriteId, cell, hp, explosive }
 ├─ base   : BaseState
 ├─ comSpawns    : List<Block>
 ├─ playerSpawns : List<Block>
 └─ gridHash : Long       FNV-1a — Host/Client 대조용
```

`cells`만이 게임 로직의 진실이고 `ground`/`decor`는 렌더 전용이다 (계획서 §41-1 로직·렌더링 분리).

---

## 5. 렌더 레이어 순서

```text
0  ground     지형 + 도로            (블록 단위)
1  decal      oldTracks, scorch, 탱크 궤적
2  hazard     WATER, ICE
3  object     BRICK, STEEL, props, BASE
4  entity     탱크, 포탄
5  canopy     FOREST — 탱크 위에 덮여 은폐 구현
6  effect     폭발, 연기, 머즐 플래시
7  overlay    *_outline (피격/방어막/관통탄)
8  hud
```

`FOREST`를 엔티티보다 **위**에 그리는 것이 원작의 숲 은폐를 재현하는 핵심이다.

---

## 6. 확장 여지

- 벽돌/강철은 블록(2×2 셀)에 스프라이트 한 장을 걸치고 각 셀이 자기 사분면만 그린다.
  절반만 무너진 벽이 원작처럼 남고, 블록이 통째로 온전하면 쿼드 하나로 합쳐 그린다.
- `generatorVersion`을 올리면 알고리즘을 바꿔도 구버전 클라이언트와 안전하게 분기할 수 있다
- 패턴 스탬프 목록과 프롭 그룹은 `assets.json` / 별도 데이터 파일로 빼면 코드 수정 없이 스테이지 성격을 튜닝할 수 있다
- 동일 seed 재사용으로 "이 맵 다시 하기" 기능을 그대로 얻는다

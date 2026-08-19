# Battle City: Local Assault — 랜덤 맵 에셋 사용 가이드

이 문서는 맵 생성 AI가 `terrain/components_atlas_128.png`의 에셋을 의미에 맞게 사용하고, 2~4인 로컬 멀티플레이에서 자연스럽고 공정하며 항상 플레이 가능한 랜덤 맵을 생성하도록 하기 위한 규칙서다.

이 문서에서 **필수**는 반드시 지켜야 하는 규칙, **권장**은 품질을 높이는 기본값이다. 시각 이미지 자체를 분석해 충돌 속성을 추론하지 말고, 이 문서와 게임 설정 데이터를 기준으로 판단한다.

---

## 1. 핵심 원칙

1. 맵 데이터에는 `BRICK`, `STEEL`, `WATER` 같은 **의미 타입**을 저장한다. `brick_intact_a` 같은 이미지 이름을 게임 규칙으로 사용하지 않는다.
2. 지형 생성과 variation 선택을 분리한다. 먼저 플레이 가능한 의미 맵을 만든 뒤 마지막 단계에서 시각 variation을 선택한다.
3. `damage`, `destroyed`, `rubble`, `warning` 에셋은 초기 배치용 variation이 아니라 런타임 상태 표현이다.
4. 동일 seed는 Host와 모든 Client에서 완전히 동일한 맵을 만들어야 한다. 비결정적 난수, 현재 시간, 기기별 난수를 사용하지 않는다.
5. 플레이어가 이동할 수 있는 경로와 탄환이 통과할 수 있는 경로를 별도로 검증한다.
6. 장식보다 게임성이 우선이다. 연결성, 본진 방어 가능성, 스폰 안전성, 플레이어 간 공정성을 통과한 맵만 사용한다.

---

## 2. 파일과 아틀라스 규격

- 런타임 아틀라스: `terrain/components_atlas_128.png`
- 메타데이터: `terrain/components_atlas.json`
- 개별 이미지: `terrain/components/*.png`
- 아틀라스 크기: 1024×768
- 셀 크기: 128×128
- 배열: 8 columns × 6 rows
- 좌표 원점: 좌상단
- 텍스처 필터: nearest-neighbor
- 권장 anchor: 셀 중앙

`terrain_variations_raw.png`는 생성 원본 보관용이며 런타임에서 사용하지 않는다.

소스 스프라이트가 128×128이어도 게임의 논리 타일 크기가 128일 필요는 없다. 렌더링 단계에서 논리 타일 크기(예: 12, 16, 24)에 맞춰 동일 비율로 축소한다.

---

## 3. 권장 맵 레이어

각 좌표는 다음 레이어를 독립적으로 가진다.

```text
MapCell
 ├─ groundLayer      // 항상 존재: DIRT, GRASS, ICE 등
 ├─ obstacleLayer    // BRICK, STEEL, WATER 등
 ├─ coverLayer       // FOREST, BUSH 등 탱크 위에 렌더링할 요소
 ├─ objectLayer      // BASE, CRATE, BARREL, ROCK, SANDBAG
 └─ logicLayer       // PLAYER_SPAWN, ENEMY_SPAWN, RESERVED_PATH 등 비가시 데이터
```

권장 렌더 순서:

```text
ground → water/ice → low obstacle/object → tank/projectile → forest/bush cover → effect/HUD
```

한 셀에 충돌 오브젝트를 두 개 이상 배치하지 않는다. 예를 들어 `STEEL + ROCK` 또는 `BRICK + CRATE` 조합은 금지한다.

---

## 4. 에셋 의미와 사용 규칙

### 4.1 Ground variation

| 의미 타입 | 사용 에셋 | 이동 | 충돌 | 초기 배치 규칙 |
|---|---|---:|---:|---|
| `DIRT` | `ground_dirt_clean_a`, `ground_dirt_stones_b` | 가능 | 없음 | 기본 바닥. 서로 섞어 사용 |
| `CRACKED_DIRT` | `ground_cracked_a`, `ground_cracked_b` | 가능 | 없음 | 벽, 폐허, 전투 지역 주변에 군집 배치 |
| `SCORCHED_DIRT` | `ground_scorched_a`, `ground_scorched_b` | 가능 | 없음 | 적 스폰, 파괴 흔적, 위험 지역 주변에 제한 사용 |
| `DRY_GRASS` | `ground_dry_grass` | 가능 | 없음 | 흙과 숲 사이 전이 영역 |
| `LUSH_GRASS` | `ground_lush_grass` | 가능 | 없음 | 숲과 물 주변 전이 영역 |

Ground variation은 게임 규칙에 영향을 주지 않는다. 전체 맵에 완전 독립 난수로 뿌리지 말고 2~6타일 크기의 지역 단위로 선택한다.

### 4.2 Brick

| 상태 | 에셋 | 용도 |
|---|---|---|
| 초기 상태 A/B | `brick_intact_a`, `brick_intact_b` | 맵 생성 시 배치 가능한 variation |
| 피해 1단계 | `brick_damage_1a`, `brick_damage_1b` | 런타임 피격 상태 |
| 피해 2단계 | `brick_damage_2a`, `brick_damage_2b` | 런타임 피격 상태 |
| 파괴 | `brick_rubble_a`, `brick_rubble_b` | 파괴 직후 또는 짧은 잔해 연출 |

권장 속성:

- 탱크 이동 차단
- 탄환 충돌
- 일반탄으로 파괴 가능
- 완전히 파괴되면 이동 가능 셀로 전환

초기 랜덤 맵에 damage/rubble 상태를 일반 벽처럼 사용하지 않는다. 폐허 테마를 의도한 경우에만 `rubble`을 **충돌 없는 장식**으로 제한적으로 사용할 수 있다.

### 4.3 Steel

사용 에셋:

- `steel_clean_a`
- `steel_riveted_b`
- `steel_reinforced_a`
- `steel_reinforced_b`

권장 속성:

- 탱크 이동 차단
- 일반탄 차단 및 도탄
- 관통탄에만 피해를 받거나 완전 불가 타입으로 설정
- 네 variation은 같은 충돌 타입을 공유

Steel은 맵을 영구 분리할 수 있으므로 긴 폐쇄 벽으로 도배하지 않는다. 2~5타일 단위의 짧은 구조물, 모서리, 본진 외곽 핵심 지지점에 사용한다.

### 4.4 Water animation

애니메이션 프레임:

```text
water_frame_1 → water_frame_2 → water_frame_3 → water_frame_4 → repeat
```

권장 속성:

- 탱크 이동 불가
- 탄환 통과 가능
- 시야 차단 없음
- 4~6 FPS 애니메이션

네 프레임을 서로 다른 water variation으로 지도에 배치하지 않는다. 모든 WATER 셀은 동일 애니메이션을 사용하되, 좌표 해시로 시작 프레임을 분산해 물결이 기계적으로 동기화되지 않도록 한다.

Water는 2×2 이상의 덩어리, 강, 연못 형태로 배치한다. 고립된 1×1 물 타일은 전체 WATER의 5% 이하로 제한한다.

### 4.5 Forest cover

사용 에셋:

- `forest_canopy_a`, `forest_canopy_b`
- `forest_dense_a`, `forest_dense_b`

권장 속성:

- 탱크 이동 가능
- 탄환 통과 가능
- 탱크와 탄환 위에 렌더링하여 시각적으로 가림
- 실제 네트워크 상태나 충돌은 숨기지 않음

Forest는 2~8타일 군집으로 배치한다. 좁은 통로 전체를 덮거나 플레이어 스폰 및 본진 바로 위를 완전히 가리지 않는다.

### 4.6 Ice

사용 에셋:

- 초기 variation: `ice_clean_a`, `ice_clean_b`
- 상태/variation: `ice_cracked_a`, `ice_cracked_b`

권장 속성:

- 이동 가능
- 충돌 없음
- 입력을 놓은 뒤 짧은 관성 또는 감속 저하 적용

Ice는 최소 2×2 영역으로 배치한다. 한 칸짜리 ice를 통로 중간에 산발적으로 놓지 않는다. `cracked` 타입을 파괴 상태로 사용하지 않는다면 clean과 같은 물리 속성을 공유한다.

### 4.7 Base states

```text
base_intact
  ├─ 보호 활성 → base_shielded
  ├─ 위험 경고 → base_warning_1 ↔ base_warning_2
  ├─ 피해 → base_damage_1 → base_damage_2
  └─ 파괴 → base_destroyed → base_rubble
```

맵 생성 시에는 `base_intact`만 배치한다. 나머지는 게임 상태에 따라 렌더러가 교체한다.

필수 규칙:

- 본진은 정확히 1개
- 본진은 플레이어 진영의 중앙 또는 중앙에 가까운 위치
- 본진과 맵 외곽 사이 최소 1타일 여유
- 본진 주변 최소 3×3을 `BASE_ZONE`으로 예약
- 본진에서 전장으로 나가는 독립 경로를 최소 3개 확보
- 본진을 STEEL로 완전히 봉쇄하지 않음
- 본진으로 향하는 단일 직선 사격로가 적 스폰까지 직접 이어지지 않음

### 4.8 Environment objects

| 에셋 | 권장 의미 | 이동 | 파괴 | 자연스러운 배치 |
|---|---|---:|---:|---|
| `sandbag_straight` | 낮은 방어물 | 차단 | 가능 | 벽과 평행하게 1~3개 |
| `sandbag_corner` | 모서리 방어물 | 차단 | 가능 | straight의 끝 또는 ㄱ자 구조 |
| `crate_intact` | 임시 장애물 | 차단 | 가능 | 벽/창고 군집 주변 |
| `crate_broken` | 파괴 상태/장식 | 가능 | 해당 없음 | 초기 사용은 드물게 |
| `barrel_steel` | 단단한 장애물 | 차단 | 설정 기반 | 1~2개, 통로 중앙 금지 |
| `barrel_hazard` | 폭발성 장애물 | 차단 | 가능 | 본진/스폰 안전구역 밖 |
| `rock_cluster` | 영구 장애물 | 차단 | 불가 | 자연 지형 및 물가 주변 |
| `bush_cluster` | 낮은 은폐 | 가능 | 선택 | forest 가장자리 및 grass 지역 |

오브젝트의 실제 충돌·폭발 규칙은 반드시 Config로 관리한다. 이미지 모양만 보고 런타임 규칙을 결정하지 않는다.

---

## 5. 권장 맵 크기

맵 크기는 플레이어 수와 동시에 존재하는 COM 수에 따라 선택한다.

| 플레이어 | 권장 크기 | 권장 동시 COM | 용도 |
|---:|---:|---:|---|
| 2 | 20×16 | 6~8 | compact |
| 3 | 24×18 | 8~10 | standard |
| 4 | 28×20 | 8~12 | large |

논리 해상도와 맵 셀 개수는 분리한다. 카메라 viewport가 맵 전체 또는 일부를 표시하도록 구성하며, 소스 이미지 해상도를 맵 크기 계산에 사용하지 않는다.

---

## 6. 맵의 필수 구역

### Enemy spawn zone

- 맵 상단의 좌측, 중앙, 우측에 기본 3개
- 각 스폰의 3×3 영역을 예약
- 스폰 셀과 바로 아래 2타일은 walkable
- 스폰끼리 너무 가까우면 안 됨: 권장 Manhattan 거리 `width × 0.25` 이상
- 플레이어 스폰 및 본진과의 최단거리: 전체 맵 높이의 55% 이상
- 스폰 출구를 BRICK 하나로 막지 않음

### Player/base zone

- 본진은 하단 중앙부 권장
- 플레이어 스폰은 본진 주변에 2~4개
- 각 플레이어 스폰의 3×3은 충돌 없는 바닥
- 두 플레이어 스폰 사이 최소 Manhattan 거리 3
- 플레이어가 시작 후 한 방향으로 최소 3타일 이동 가능

### Combat center

- 중앙 30~40% 영역에 교전 가능한 열린 공간 2개 이상
- 열린 공간 하나의 최대 크기는 전체 맵의 12% 이하
- 중앙을 완전히 빈 광장이나 완전한 벽 미로로 만들지 않음

---

## 7. 자연스러운 분포 비율

다음 비율은 초기 의미 맵의 권장 범위다. Ground는 모든 셀 아래에 존재하므로 obstacle 비율과 별도로 계산한다.

| 요소 | 전체 셀 대비 권장 범위 |
|---|---:|
| 이동 가능한 셀 | 48~65% |
| BRICK | 16~26% |
| STEEL/ROCK 영구 장애물 | 6~12% |
| WATER | 4~10% |
| FOREST/BUSH cover | 6~14% |
| ICE | 0~8% |
| 기타 환경 오브젝트 | 2~5% |

한 종류를 셀 단위 독립 난수로 흩뿌리지 않는다. 권장 군집 크기:

- Brick structure: 2~7타일
- Steel structure: 2~5타일
- Water: 4~18타일
- Forest: 3~12타일
- Ice: 4~12타일
- Decoration/object: 1~3타일

인접한 동일 타입 타일의 비율이 55~80%가 되도록 군집을 형성하면 자연스럽다. 단, 거대한 단일 군집이 전체 맵을 둘로 분리하면 안 된다.

---

## 8. 권장 생성 순서

```text
1. seed와 플레이어 수 결정
2. 맵 크기 선택
3. 본진, 플레이어 스폰, 적 스폰 예약
4. 본진↔중앙↔적 스폰을 잇는 핵심 이동 경로 생성
5. 보조 경로와 순환 경로 추가
6. Water/Steel 같은 영구 지형을 군집 배치
7. Brick 구조물을 경로 사이에 배치
8. Forest/Ice 및 환경 오브젝트 배치
9. 연결성·사격선·공정성 검증
10. 실패 구역 수선 또는 seed 재생성
11. 좌표 기반 variation 선택
12. 의미 맵과 seed 직렬화
```

경로를 장애물보다 먼저 만든다. 장애물을 먼저 무작위 배치한 뒤 빈 공간을 찾는 방식은 막힌 스폰과 불공정한 본진 경로를 만들기 쉽다.

---

## 9. 경로와 연결성 검증

다음 두 그래프를 별도로 만든다.

### Hard-walkable graph

현재 상태에서 탱크가 즉시 이동 가능한 셀만 포함한다.

```text
통과: DIRT, GRASS, ICE, FOREST, BUSH
차단: BRICK, STEEL, WATER, BASE, solid object
```

### Soft-walkable graph

파괴 가능한 BRICK/CRATE/SANDBAG을 통과 비용이 높은 셀로 포함한다.

필수 검증:

- 모든 플레이어 스폰은 hard graph에서 전투 중앙까지 연결
- 각 플레이어 스폰에서 최소 2개의 적 스폰 방향으로 hard path 존재
- 본진에서 중앙으로 나가는 서로 다른 첫 방향이 최소 3개
- soft path만 존재하는 구역에 플레이어를 스폰하지 않음
- walkable 영역 중 가장 큰 연결 컴포넌트가 전체 walkable 셀의 90% 이상
- 2×2 이상의 회전/회피 공간이 맵 여러 곳에 존재

단일 타일 폭 통로는 전체 주요 경로 길이의 20% 이하로 제한한다. 주요 통로는 가능하면 2타일 이상 폭을 사용한다.

---

## 10. 공정성 검증

플레이어 `i`에 대해 다음 값을 계산한다.

```text
distanceToCenter[i]
distanceToNearestEnemySpawn[i]
distanceToBase[i]
reachableAreaWithin10Steps[i]
coverCountWithin6Steps[i]
```

권장 허용 편차:

- 중앙까지 거리: 플레이어 평균 대비 ±15%
- 가장 가까운 적 스폰까지 거리: 플레이어 평균 대비 ±18%
- 10스텝 내 이동 가능 면적: 최대/최소 비율 1.25 이하
- 6스텝 내 엄폐 수: 최대/최소 차이 3 이하

완전 대칭 맵은 필요하지 않지만, 한 플레이어만 물/강철에 갇히거나 본진 접근로를 독점해서는 안 된다.

---

## 11. 본진 방어 구조

권장 초기 패턴은 완전 고정하지 말고 다음 조건을 조합한다.

- 본진 인접 8칸 중 3~5칸은 BRICK/SANDBAG 방어물
- 인접 방어물 중 STEEL은 0~2칸
- 전면 또는 측면에 최소 2개의 출입구
- 진입 경로 중 하나는 짧고 위험하며, 다른 하나는 길고 안전하게 구성 가능
- 본진 전방 4~8타일 사이에 첫 교전 지점 배치
- `barrel_hazard`는 본진 반경 4 이내 금지
- WATER로 본진을 둘러싸지 않음

본진이 `base_shielded` 상태일 때도 맵 충돌 구조는 바뀌지 않고 렌더링과 피해 규칙만 바뀐다.

---

## 12. Variation 선택

Variation은 `seed + x + y + semanticType`으로 결정한다.

```text
variationIndex = hash(seed, x, y, semanticType) mod variationCount
```

권장 가중치 예시:

```text
DIRT:
  ground_dirt_clean_a    45
  ground_dirt_stones_b   25
  ground_cracked_a       10
  ground_cracked_b       10
  ground_dry_grass        5
  ground_lush_grass       5

BRICK intact:
  brick_intact_a         55
  brick_intact_b         45

STEEL:
  steel_clean_a          35
  steel_riveted_b        30
  steel_reinforced_a     20
  steel_reinforced_b     15
```

완전 독립 variation 대신 인접 셀의 biome tag를 고려한다.

- 물 주변: `lush_grass` 가중치 증가
- Forest 주변: grass 계열 증가
- 적 스폰/전투 중심: scorched 계열 증가
- Brick rubble 주변: cracked 계열 증가

상태 variation은 좌표 난수로 선택하지 않는다. 예를 들어 BRICK은 피격 횟수에 따라 damage 단계로 전환하고, A/B 스타일은 intact에서 선택한 계열을 유지한다.

---

## 13. 초기 맵에서 사용하면 안 되는 에셋

특별한 테마 규칙이 없다면 다음 에셋을 정상 상태 타일로 배치하지 않는다.

- `brick_damage_1a`, `brick_damage_1b`
- `brick_damage_2a`, `brick_damage_2b`
- `brick_rubble_a`, `brick_rubble_b`
- `base_shielded`
- `base_warning_1`, `base_warning_2`
- `base_damage_1`, `base_damage_2`
- `base_destroyed`, `base_rubble`
- `crate_broken`

이들은 런타임 상태 전환 또는 의도된 폐허 테마에서만 사용한다.

---

## 14. AI 출력 계약

맵 생성 AI는 이미지 인덱스 배열이 아니라 다음 의미 데이터를 출력해야 한다.

```json
{
  "version": 1,
  "seed": 20260819,
  "width": 24,
  "height": 18,
  "playerCount": 3,
  "theme": "mixed_ruins",
  "base": { "x": 12, "y": 16 },
  "playerSpawns": [
    { "player": 1, "x": 10, "y": 15, "direction": "UP" },
    { "player": 2, "x": 12, "y": 14, "direction": "UP" },
    { "player": 3, "x": 14, "y": 15, "direction": "UP" }
  ],
  "enemySpawns": [
    { "x": 2, "y": 1, "direction": "DOWN" },
    { "x": 12, "y": 1, "direction": "DOWN" },
    { "x": 21, "y": 1, "direction": "DOWN" }
  ],
  "layers": {
    "ground": "RLE 또는 width×height 의미 타입 배열",
    "obstacle": "RLE 또는 width×height 의미 타입 배열",
    "cover": "RLE 또는 width×height 의미 타입 배열",
    "object": "RLE 또는 width×height 의미 타입 배열"
  },
  "validation": {
    "hardConnectedRatio": 0.96,
    "baseExitCount": 3,
    "fairnessScore": 0.89,
    "passed": true
  }
}
```

허용 의미 타입 권장 목록:

```text
Ground:   DIRT, CRACKED_DIRT, SCORCHED_DIRT, DRY_GRASS, LUSH_GRASS, ICE
Obstacle: NONE, BRICK, STEEL, WATER
Cover:    NONE, FOREST, BUSH
Object:   NONE, BASE, SANDBAG, CRATE, STEEL_BARREL, HAZARD_BARREL, ROCK
Logic:    NONE, PLAYER_SPAWN, ENEMY_SPAWN, RESERVED_PATH, BASE_ZONE
```

렌더러는 의미 타입과 상태를 에셋 이름으로 변환한다. AI가 atlas row/column을 직접 결정하지 않는 것이 원칙이다.

---

## 15. 맵 품질 점수

100점 기준 권장 배점:

| 항목 | 점수 |
|---|---:|
| 모든 필수 경로 연결 | 30 |
| 플레이어 간 공정성 | 20 |
| 본진 방어와 출입 균형 | 15 |
| 스폰 안전성과 분리 | 15 |
| 자연스러운 군집/variation | 10 |
| 시야, 엄폐, 개방 공간 다양성 | 10 |

- 85점 이상: 사용
- 75~84점: 자동 수선 후 재검증
- 75점 미만: seed 폐기 후 재생성
- 필수 규칙 위반: 점수와 무관하게 폐기

---

## 16. 자동 수선 규칙

검증 실패 시 전체 맵을 즉시 폐기하기 전에 다음을 순서대로 시도한다.

1. 막힌 스폰 앞의 BRICK/오브젝트 1~2개 제거
2. STEEL을 BRICK 또는 DIRT로 완화
3. WATER 군집에 2타일 폭 육로 생성
4. 지나치게 긴 1타일 통로 옆 장애물 제거
5. 본진 출구가 부족하면 측면 방어물 1개 제거
6. 플레이어별 초기 이동 면적 차이가 크면 불리한 지역 장애물 감소
7. 수선 후 전체 검증을 처음부터 다시 실행

수선 횟수가 8회를 초과하면 seed를 폐기한다.

---

## 17. 피해야 할 맵 패턴

- 모든 셀을 독립 난수로 선택한 소금·후추 형태
- 1×1 WATER/ICE/FOREST가 과도하게 흩어진 형태
- 맵을 완전히 가르는 STEEL 또는 WATER 장벽
- 적 스폰에서 본진까지 장애물 없는 직선 사격로
- 본진 출구가 하나뿐인 구조
- 플레이어 스폰 바로 앞 BRICK/STEEL
- 한 플레이어만 넓은 공간 또는 엄폐를 독점하는 구조
- forest가 본진과 모든 탱크를 항상 가리는 구조
- 초기 맵에 파괴/경고 상태 에셋을 무작위 사용
- variation 선택 때문에 충돌 타입이 바뀌는 구현
- Host와 Client가 서로 다른 난수 순서로 variation을 결정하는 구현

---

## 18. AI용 생성 프롬프트 템플릿

```text
Battle City: Local Assault의 랜덤 맵 의미 데이터를 생성한다.

입력:
- seed: {seed}
- playerCount: {2..4}
- width: {width}
- height: {height}
- theme: {theme}

필수 규칙:
- RANDOM_MAP_ASSET_GUIDE.md를 따른다.
- 본진 1개, 플레이어 스폰 playerCount개, 상단 적 스폰 3개를 배치한다.
- 모든 플레이어 스폰은 hard-walkable graph에서 전투 중앙과 연결한다.
- 본진 출구는 최소 3개다.
- 의미 타입만 출력하고 damage/destroyed 에셋을 초기 타일로 사용하지 않는다.
- 장애물은 군집으로 배치하되 맵을 완전히 분리하지 않는다.
- 플레이어별 경로 거리와 초기 이동 면적의 허용 편차를 지킨다.
- 생성 후 validation 결과와 품질 점수를 함께 출력한다.

출력:
- JSON만 출력한다.
- seed, dimensions, base, playerSpawns, enemySpawns, layers, validation을 포함한다.
```

---

## 19. 최종 체크리스트

```text
[ ] base_intact가 정확히 1개인가?
[ ] 플레이어 수와 player spawn 수가 같은가?
[ ] 적 spawn이 상단 좌/중/우에 3개 존재하는가?
[ ] 모든 스폰 주변 3×3이 안전한가?
[ ] 본진 출구가 3개 이상인가?
[ ] 모든 플레이어가 hard path로 중앙에 갈 수 있는가?
[ ] 가장 큰 walkable 연결 영역이 90% 이상인가?
[ ] WATER/STEEL이 맵을 완전히 분리하지 않는가?
[ ] 주요 통로가 대부분 2타일 이상 폭인가?
[ ] 적 스폰에서 본진까지 직접 사격선이 없는가?
[ ] 플레이어 간 거리/엄폐 편차가 허용 범위인가?
[ ] 지형이 의미 있는 군집을 이루는가?
[ ] damage/destroyed 상태가 초기 배치에 없는가?
[ ] variation이 seed와 좌표로 결정되는가?
[ ] Host/Client 결과 해시가 동일한가?
```

이 체크리스트를 모두 통과한 이후에만 맵을 게임 세션에 사용한다.

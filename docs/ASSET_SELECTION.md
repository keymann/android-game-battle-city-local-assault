# 리소스 선별 결과 (Asset Selection)

> 지형과 환경 오브젝트는 프로젝트 자체 리소스(`assets/renewal.png`)에서 잘라 쓴다.
> 회전하는 유닛(탱크·포탄·폭발)만 Kenney *Top-down Tanks Redux*(CC0),
> HUD 폰트와 게이지는 Kenney *Desert Shooter Pack*(CC0)을 유지한다.

---

## 1. 리소스 출처

| 출처 | 규격 | 용도 |
|---|---|---|
| **`assets/renewal.png`** (자체 리소스) | 63장 | **지형·구조물·환경 오브젝트 전부** |
| **`assets/custom/본진_건물.png`** (자체 리소스) | 4프레임 | 본진 건물과 파괴 애니메이션 |
| Kenney **Top-down Tanks Redux** (CC0) | 187 | 탱크(몸체+포신), 포탄, 폭발, 궤도자국 |
| Kenney **Desert Shooter Pack** (CC0) | 198 + 40 | HUD 비트맵 폰트, 게이지 바, UI 아이콘 |

Kenney *Tiny Battle* / *Tiny Town* / *Tiny Dungeon* 은 한때 지형을 담당했으나
자체 환경 시트로 전부 교체됐다. 아틀라스에는 남아 있지만 매니페스트가 참조하지 않는다.
*RPG Urban Pack* 과 *Pico-8 City* 는 팔레트·해상도가 맞지 않아 처음부터 쓰지 않았다.

---

## 2. 자체 환경 시트에서 무엇을 뽑았나

전달받은 `renewal.png` 는 라벨과 색상 팔레트가 함께 든 **설명용 시트**다.
`tools/cut_environment.py` 가 좌표를 실측해 63장을 잘라 낸다.

| 분류 | 장수 | 이름 |
|---|---:|---|
| 벽돌 벽 | 3 + 잔해 2 | `env_brick_*` |
| 강철 벽 | 4 + 잔해 1 | `env_steel_*` |
| 물 | 3 + 우물 1 | `env_water_*`, `env_well` |
| 나무 / 풀 | 5 | `env_tree_*`, `env_bush`, `env_stump_*`, `env_flower` |
| 바닥 / 도로 | 6 + 회전본 1 | `env_ground_*`, `env_road_*` |
| 진지 / 모래주머니 | 4 | `env_sandbag_*`, `env_barricade_*` |
| 드럼통 / 유류탱크 | 6 | `env_barrel_*` |
| 상자 / 보급품 | 5 | `env_crate_*`, `env_supply_*` |
| 울타리 / 장애물 | 4 | `env_fence_*`, `env_barrier_*` |
| 건물 / 구조물 | 4 | `env_building_*` |
| 탑 / 초소 | 3 | `env_tower_*` |
| 장식 | 5 | `env_lamp`, `env_flag_*`, `env_sign`, `env_stump_1` |
| 특수 | 6 | `env_bridge_*`, `env_crops`, `env_crater`, `env_tire*` |

시트의 **이펙트 줄은 "참고용"** 이라 쓰지 않는다. 폭발은 탱크와 같은 계열이어야
자연스러워 Top-down Tanks Redux 것을 그대로 둔다.

### 이어 붙는 타일과 하나씩 놓는 물건을 다르게 다룬다

| 종류 | 처리 |
|---|---|
| **tile** (지형·도로·물·벽) | 바깥의 어두운 테두리를 깎아 낸다. 남으면 화면 전체에 격자가 생긴다. 정확히 32×32 |
| **object** (드럼통·건물 등) | 테두리가 윤곽선이라 그대로 둔다. 비율을 지켜 축소 |

테두리 두께는 종류마다 다르다. 바닥·도로는 두껍게(9px), 물은 바깥 물결을 조금 남기고(6px),
벽돌·강철은 벽 블록의 윤곽이라 살짝만(5px) 다듬는다.

**y 좌표는 줄 전체가 아니라 타일의 실제 세로 범위를 잰 값이다.** 줄 범위를 그대로 쓰면
아래쪽 빈 칸이 딸려 와 가로 이음선이 생긴다. 처음에 이 때문에 화면 전체에 줄무늬가 생겼다.

### 도로는 직선만 중앙선을 넣는다

새 시트에는 16-mask 오토타일 세트가 없다. 직선 구간(N·S 또는 E·W)만 중앙선 타일을 쓰고
모서리·분기·고립은 민무늬 아스팔트로 둔다. 가로 직선용 타일은 세로 타일을 90도 돌려
아틀라스에 미리 넣어 둔다 — `StageData` 는 스프라이트 인덱스만 들고 회전값이 없기 때문이다.

흙 구역도 전이 타일이 없어 경계가 각지게 떨어진다. 원작도 타일 경계가 각졌으므로 그대로 둔다.

---

## 3. 런타임 텍스처 2장

| 파일 | 원본 | 크기 | 필터 | 내용 |
|---|---|---:|---|---|
| `atlas/tiles.png` + `tiles.xml` | 환경 시트 + 본진 시트 + Desert Shooter + (미사용 Tiny 계열) | 352×832 | **NEAREST** | 16px 700 + 32×40 본진 4 + 환경 63 |
| `atlas/units.png` + `units.xml` | Top-down Tanks Redux (Retina) | 1124×1128 | **LINEAR** | 탱크·포탄·폭발 187 |

`tiles` 는 `tools/build_tile_atlas.sh` 가 만든다. 스프라이트 이름의 접두사가 출처다.

```
battle_037   Tiny Battle #37        town_052    Tiny Town #52
dungeon_040  Tiny Dungeon #40       ui_char_A   Desert Shooter 폰트
env_brick_0  환경 시트 (자체)        base_0      본진 건물 (자체)
ui_char_A    Desert Shooter 폰트     tankBody_blue  Top-down Tanks Redux
```

### 필터를 나눈 이유

픽셀아트를 LINEAR 로 확대하면 도트가 뭉개지고, 여백 없는 격자 아틀라스에서
이웃 타일 색이 새어 나와 **지형에 이음선**이 생긴다. 반대로 회전하는 벡터 스프라이트를
NEAREST 로 뽑으면 가장자리에 계단이 진다. 그래서 렌더러가 텍스처마다 샘플러를 고른다.

---

## 4. 좌표계

```text
block = 64 logical px   → 지형 타일 1장 = 탱크 1대 (원작의 16px 타일에 대응)
cell  = 32 logical px   → BRICK / STEEL 파괴 최소 단위 (원작의 8px 벽돌 4분할)
1 block = 2 × 2 cell
```

16px 원본을 64px 블록에 그리므로 **4배 확대**다. NEAREST 라 도트가 그대로 살아 있다.

---

## 5. TileType ↔ 리소스 매핑

계획서 §30 의 TileType 을 그대로 유지하고, 판정 규칙도 원작과 동일하다.

| TileType | 탱크 | 포탄 | 리소스 |
|---|---|---|---|
| `EMPTY` | 통과 | 통과 | 잔디 / 흙 / 모래 `env_ground_*`, 도로 `env_road_*` |
| `BRICK` | 차단 | **파괴** | `env_brick_0~2` |
| `STEEL` | 차단 | **관통탄만** | `env_steel_0~3` |
| `WATER` | 차단 | **통과** | `env_water_0~2` (3프레임 애니) |
| `FOREST` | 통과 | 통과 | `env_tree_0/1`, `env_bush` — 엔티티 **위** 캐노피로 그려 은폐 |
| `ICE` | 통과(관성) | 통과 | `env_water_0` + `#DFF6FF` 틴트 (시트에 얼음 타일이 없다) |
| `BASE` | 차단 | 파괴 → GAME OVER | 본진 건물 4프레임 `base_0~3` (아래 §5-1 참고) |

### 물은 프레임 3장

전용 시트라 열린 수면 프레임이 3장 있다. 밝기 보정 없이 프레임 교대만으로 넘실거린다.
얼음은 시트에 없어 물 타일을 밝게 물들여 쓴다.

이전 타일셋에서는 물 타일 대부분이 해안 전이 타일이라 잔디가 몇 픽셀 섞여 연못 안쪽에
조각이 박히는 일이 두 번 있었다. 지금은 전용 시트를 쓰지만 자르는 좌표가 한 칸만 밀려도
옆 타일이 딸려 오므로 `WaterTileTest` 의 픽셀 검사는 그대로 둔다.

---

### 본진 건물

프로젝트에 직접 넣은 `assets/custom/본진_건물.png` 에서 뽑는다. 전달받은 파일은
라벨·화살표·색상 변형·방향별 버전이 함께 든 **설명용 시트**라 게임에 필요한
파괴 애니메이션 4프레임만 오려 낸다.

| 프레임 | 상태 |
|---|---|
| `base_0` | 정상 |
| `base_1` | 손상 |
| `base_2` | 심한 손상 → **폭발 후 잔해로 남는다** |
| `base_3` | 폭발 |

프레임마다 경계 상자가 다르다(잔해가 밖으로 튄다). 그대로 쓰면 재생 중 건물이 흔들리므로
**가로 중앙 + 바닥선**을 맞춘 공통 캔버스에 얹은 뒤 한 번에 32×40 으로 줄인다.
미리 줄여서 합치면 프레임마다 반올림이 달라져 1px 씩 떨린다.

프레임 사이에 안내용 화살표(▶)가 있어 경계를 넉넉히 잡으면 같이 딸려 온다.
`tools/cut_base_frames.py` 의 좌표는 화살표를 피해 실측한 값이다.

지형(16px)보다 촘촘한 32px 이지만, 본진은 화면에 하나뿐인 주인공 오브젝트라 허용한다.
스프라이트가 블록보다 세로로 길어(깃발) **바닥을 블록 하단에 맞추고** 위로 삐져나오게 그린다.
파괴 애니메이션은 게임 상태가 아니라 연출이므로 렌더러가 시간을 센다. (계획서 §41-1)

---

### 한 칸짜리 소품은 통째로 그린다

벽은 블록(2×2 셀)에 스프라이트 한 장을 걸치고 셀마다 자기 사분면을 그린다. 그래야
절반만 무너진 벽이 남는다. 하지만 드럼통처럼 **한 칸짜리 물건에 같은 규칙을 쓰면
4분의 1만 보인다.** `StageData.wholeSpriteCells` 에 표시된 셀은 사분면으로 쪼개지 않는다.

건물·감시탑은 반대로 블록 네 칸을 같은 스프라이트로 채운다. 네 칸이 모두 같으면
렌더러가 블록 하나로 합쳐 그리므로 큰 그림이 온전히 보인다.

---

## 6. 탱크 매핑

| 슬롯 | 몸체 | 공격형 포신 | 방어형 | 스피드형 | 포탄 | 진영기 |
|---|---|---|---|---|---|---|
| P1 | `tankBody_blue` | `tankBlue_barrel3` | `_barrel2` | `_barrel1` | `bulletBlue1~3` | `battle_052` |
| P2 | `tankBody_green` | `tankGreen_barrel3` | `_barrel2` | `_barrel1` | `bulletGreen1~3` | `battle_034` |
| P3 | `tankBody_red` | `tankRed_barrel3` | `_barrel2` | `_barrel1` | `bulletRed1~3` | `battle_070` |
| P4 | `tankBody_sand` | `tankSand_barrel3` | `_barrel2` | `_barrel1` | `bulletSand1~3` | `battle_088` |

| COM | 몸체 | 포신 |
|---|---|---|
| 공격형 | `tankBody_bigRed` | `tankRed_barrel3` |
| 방어형 | `tankBody_darkLarge` | `tankDark_barrel2` |
| 스피드형 | `tankBody_dark` | `tankDark_barrel1` |
| 엘리트(확장) | `tankBody_huge` | `tankDark_barrel3` |

몸체와 포신은 **탱크 중심을 공통 축**으로 회전한다.
몸체는 `origin(0.5, 0.5)`, 포신은 포미가 중심에 오도록 `origin(0.5, 1.0)` 이다.

### 특수기 연출

새 스프라이트를 만들지 않고 기존 리소스를 색과 크기로 변형해 만든다. (계획서 §6)

| 특수기 | 연출 |
|---|---|
| 관통탄 | `shotLarge` 탄두 + `shotRed` 꼬리를 `#FFD24A` 로 물들여 일반 포탄과 구분 |
| 방어막 | 탱크 `*_outline` 을 1.28배 `#66CCFF` 로 키워 3Hz 로 맥동 |
| 대시 | 진행 반대 방향으로 몸체 잔상 3장을 점점 옅게 |

---

### 지형과 탱크 색 분리

탱크 뒤에 `*_outline` 을 1.05배 `#101018` 로 얇게 깔아 어떤 지형 위에서도 형태가 읽히게 한다.
원본 스프라이트에 이미 외곽선이 있어 과하게 주면 검은 덩어리로 보인다.

---

## 7. 환경 오브젝트 12개 그룹

스테이지마다 3~5개 그룹만 뽑아 조합하므로 판마다 성격이 달라진다.

| 그룹 | 성격 | 배치 |
|---|---|---|
| `fuelBarrel` | **폭발** (반경 3 cell) | 한 칸 |
| `supplyBarrel` | **폭발** (반경 2 cell) | 한 칸 |
| `supplyCrate` | 파괴 가능 엄폐 | 한 칸 |
| `sandbagLine` · `barricadeLine` | 파괴 가능 방벽 | 선형 |
| `fenceLine` | 파괴 불가 방벽 | 선형 |
| `roadBarrier` | 파괴 가능 방벽 HP2 | 선형 |
| `building` · `watchtower` | 파괴 불가 구조물 | **블록(2×2 셀)** |
| `rubble` · `streetProp` · `junk` | 장식 (충돌 없음) | 자유 배치 |

---

## 8. HUD

Desert Shooter 의 비트맵 폰트가 들어와 숫자뿐 아니라 **문자**도 찍는다.

| 요소 | 리소스 |
|---|---|
| 폰트 | `ui_char_A~Z`, `ui_digit_0~9` |
| 하트(Life) | `battle_195` |
| 해골(탈락) | `ui_054` |
| 자물쇠(리스폰 대기) | `ui_076` |
| 특수기 게이지 | `ui_140`(채움) / `ui_158`(빈칸) — 가운데 조각을 가로로 늘여 쓴다 |
| 플레이어 진영기 | `battle_052/034/070/088` |

폰트 시트는 한 줄에 이어지지 않는다. 18열이라 `A~M` 다음에 게이지 바가 끼고 `N~Z` 가
다음 줄에서 시작한다. 26자를 연속으로 매기면 `N~R` 자리에 바가 들어온다.

---

## 9. 밸런스 데이터 분리

리소스 매핑(`manifest/assets.json`)과 **게임 규칙·능력치**(`manifest/balance.json`)를 파일로 나눴다.
전자는 "무엇으로 그리는가", 후자는 "어떻게 동작하는가"다. (계획서 §7, §41-19)

| 섹션 | 내용 |
|---|---|
| `rules` | Life 3 / HP 100 / 데미지 계수 / 총 COM 수 / 동시 COM 수 / 리스폰 지연 / 점수 |
| `units` | 계획서의 1~4 **등급값**을 px/s·초로 바꾸는 계수 |
| `playerTanks` · `enemyTanks` | 능력치와 특수기 |
| `enemyMix` · `tieBreak` | COM 출현 가중치, 동점 처리 순위 |

---

## 10. 아틀라스 재생성

```bash
tools/build_tile_atlas.sh
```

모든 출처를 합쳐 `atlas/tiles.png` 와 `atlas/tiles.xml` 을 다시 만든다.

| 스크립트 | 역할 |
|---|---|
| `tools/cut_environment.py` | 환경 시트에서 63장 추출 + 구역 배치 |
| `tools/cut_base_frames.py` | 본진 파괴 4프레임 추출 |
| `tools/write_atlas_xml.py` | 서술자 작성 |
배치가 바뀌어도 이름은 그대로라 매니페스트는 손대지 않아도 된다.

---

## 11. 크레딧

CC0 이므로 법적 의무는 없으나 `CREDITS.md` 에 명시한다.

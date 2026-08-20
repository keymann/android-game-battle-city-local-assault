# 리소스 선별 결과 (Asset Selection)

> 대상: `assets/` 아래 Kenney 팩 7종. 전부 **CC0 1.0** (상업적 사용 가능, 크레딧 권장)
> 결정: **월드는 16px 픽셀아트로 통일**하고, 회전하는 유닛만 벡터풍 팩을 쓴다.

---

## 1. 팩별 판정

| 팩 | 규격 | 판정 | 용도 |
|---|---|---|---|
| **Top-down Tanks Redux** (`asset1`) | 64px 벡터풍 | **유닛 전용** | 탱크(몸체+포신 분리), 포탄, 폭발, 궤도자국 |
| **Tiny Battle** (`asset2`) | 16px 픽셀 | 채택 | 물, 도로, 본진 깃발, 하트 |
| **Tiny Town** | 16px 픽셀 | 채택 | 잔디·흙·자갈, 나무 26종, 벽돌벽, 울타리, 프롭 |
| **Tiny Dungeon** | 16px 픽셀 | 채택 | 석재벽, 철창, 배럴, 상자, 바닥 자국 |
| **Desert Shooter** | 16px / 24px 픽셀 | 부분 채택 | **비트맵 폰트(A~Z, 0~9)**, 게이지 바, UI 아이콘 |
| **RPG Urban Pack** | 16px 픽셀 | 미채택 | 팔레트 톤이 Tiny 계열과 어긋난다 |
| **Pico-8 City** | 8px 픽셀 | 미채택 | 해상도가 절반이라 같은 화면에 섞을 수 없다 |

Desert Shooter 의 **지형 타일 234장은 쓰지 않는다**. 보라·청록 계열 사막/실내 팔레트라
Tiny 계열의 따뜻한 색과 나란히 두면 다른 게임처럼 보인다. 폰트와 UI 만 가져왔다.

---

## 2. 왜 픽셀로 통일했는가

`asset1` 은 이미 **187/187 전부 사용 중**이라 그 안에서 지형·환경을 더 풍성하게 만들 여지가 없었다.
새로 들어온 팩은 다섯 개 모두 픽셀아트이므로, 환경을 보강하려면 픽셀로 가는 수밖에 없었다.

| | asset1 단독 (이전) | Tiny 계열 3종 (현재) |
|---|---:|---:|
| 지형 | 잔디/모래 2종 + 도로 36 | 잔디/흙/자갈 + 전이 타일 + 도로 16-mask |
| 나무 | 8 | **26** |
| 벽·구조물 | 상자·모래주머니·펜스 9 | 벽돌·석재·목재·철창 **33** |
| 폰트 | 없음 | **A~Z, 0~9** |

`Tiny Battle` / `Tiny Town` / `Tiny Dungeon` 은 같은 작가의 같은 계열이라
외곽선 두께와 팔레트가 정확히 맞는다. 셋을 합쳐도 한 팩처럼 보인다.

### 탱크만 예외

탱크는 `Top-down Tanks Redux` 를 유지한다. **몸체와 포신이 분리**돼 있어
포신 굵기 1→2→3 이 스피드/방어/공격 서열을 그대로 보여 주고, 회전 표현이 자연스럽다.
Tiny 계열 유닛은 왼쪽 고정 방향 단일 스프라이트라 이 표현이 불가능하다.

---

## 3. 런타임 텍스처 2장

| 파일 | 원본 | 크기 | 필터 | 내용 |
|---|---|---:|---|---|
| `atlas/tiles.png` + `tiles.xml` | Tiny Battle + Tiny Town + Tiny Dungeon + Desert Shooter + 본진 시트 | 352×592 | **NEAREST** | 16px 타일 660 + 24px 이펙트 40 + 32×40 본진 4 |
| `atlas/units.png` + `units.xml` | Top-down Tanks Redux (Retina) | 1124×1128 | **LINEAR** | 탱크·포탄·폭발 187 |

`tiles` 는 `tools/build_tile_atlas.sh` 가 네 팩을 합쳐 만든다. 스프라이트 이름의 접두사가 출처다.

```
battle_037   Tiny Battle #37        town_052    Tiny Town #52
dungeon_040  Tiny Dungeon #40       ui_char_A   Desert Shooter 폰트
fx_025       Desert Shooter 버스트   base_0      본진 건물 (프로젝트 자체 리소스)
tankBody_blue  Top-down Tanks Redux
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
| `EMPTY` | 통과 | 통과 | 잔디 `town_000~002`, 흙 `town_039~042`, 자갈 `town_043`, 도로 16-mask |
| `BRICK` | 차단 | **파괴** | 붉은 벽돌벽 `town_052~054`,`064~066` / 목재벽 `town_072~075` |
| `STEEL` | 차단 | **관통탄만** | 석재벽 `dungeon_036~041`,`057~059` / 돌벽 `town_048~062` / 철창 `dungeon_069~081` |
| `WATER` | 차단 | **통과** | `battle_037` |
| `FOREST` | 통과 | 통과 | 나무 26종 — 엔티티 **위** 캐노피로 그려 은폐 |
| `ICE` | 통과(관성) | 통과 | `battle_037` + `#DFF6FF` 틴트 |
| `BASE` | 차단 | 파괴 → GAME OVER | 본진 건물 4프레임 `base_0~3` (아래 §5-1 참고) |

### 물 타일은 하나뿐이다

Tiny Battle 의 물 타일 30종 중 잔디·모래가 **전혀** 섞이지 않은 열린 수면은 `battle_037` 하나다.
`#38`, `#91`, `#92` 는 눈으로는 구분이 안 되지만(`#92` 는 잔디가 8픽셀뿐) 연못 안쪽에 조각이 박힌다.
픽셀 히스토그램으로 골라냈고, 같은 검사를 `WaterTileTest` 로 남겨 두었다.

프레임이 하나뿐이라 물결은 셀 위치별 **밝기 위상차**로 만든다.

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

### 도로는 16-mask 완전 세트

`battle_108~111 / 126~129 / 144~147 / 162~165` 는 도로 영역 오토타일이다.
이웃 4방향 비트마스크 16가지가 **빠짐없이** 대응돼 직선·모서리·삼거리·사거리가 모두 정확히 나온다.

| mask | 타일 | mask | 타일 |
|---:|---|---:|---|
| 0 (고립) | `battle_108` | 8 (W) | `battle_111` |
| 1 (N) | `battle_162` | 9 (N·W) | `battle_165` |
| 2 (E) | `battle_109` | 10 (E·W) | `battle_110` |
| 3 (N·E) | `battle_163` | 11 (N·E·W) | `battle_164` |
| 4 (S) | `battle_126` | 12 (S·W) | `battle_129` |
| 5 (N·S) | `battle_144` | 13 (N·S·W) | `battle_147` |
| 6 (E·S) | `battle_127` | 14 (E·S·W) | `battle_128` |
| 7 (N·E·S) | `battle_145` | 15 (전부) | `battle_146` |

흙 구역은 `town_012~038` 의 3×3 나인슬라이스로 잔디와의 경계를 처리한다.

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

### 지형과 탱크 색 분리

탱크 뒤에 `*_outline` 을 1.05배 `#101018` 로 얇게 깔아 어떤 지형 위에서도 형태가 읽히게 한다.
원본 스프라이트에 이미 외곽선이 있어 과하게 주면 검은 덩어리로 보인다.

---

## 7. 환경 오브젝트 11개 그룹

스테이지마다 3~5개 그룹만 뽑아 조합하므로 판마다 성격이 달라진다.

| 그룹 | 성격 | 리소스 |
|---|---|---|
| `explosiveBarrel` | **폭발** (반경 3 cell) | `town_105`, `dungeon_029` |
| `fuelBarrel` | **폭발** (반경 2 cell) | `town_130`, `dungeon_073`, `dungeon_082` |
| `supplyCrate` | 파괴 가능 엄폐 | `dungeon_089~091`, `town_104`, `town_107` |
| `armorBlock` | 파괴 불가 엄폐 | `town_120~122`, `dungeon_054/055` |
| `woodFence` | 파괴 가능 방벽(선형) | `town_044~047`, `town_080~082` |
| `ironFence` | 파괴 불가 방벽(선형) | `dungeon_069~071`, `dungeon_079~081` |
| `haystack` | 파괴 가능 엄폐 | `town_092~094` |
| `rubble` | 장식 | `dungeon_009/012/024/025` |
| `toolShed` | 장식 | `town_115~131` |
| `groundMark` | 장식 | `dungeon_060~062` |
| `flora` | 장식 | `town_029`, `town_017`, `town_005` |

---

## 8. HUD

Desert Shooter 의 비트맵 폰트가 들어와 숫자뿐 아니라 **문자**도 찍는다.

| 요소 | 리소스 |
|---|---|
| 폰트 | `ui_char_A~Z`, `ui_digit_0~9` |
| 하트(Life) | `battle_195` |
| 해골(탈락) | `ui_054` |
| 자물쇠(리스폰 대기) | `ui_076` |
| 게이지 바 | `ui_139~141`(채움) / `ui_157~159`(빈칸) — Phase 4 쿨타임 UI 용 |
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

네 팩과 본진 시트를 합쳐 `atlas/tiles.png` 와 `atlas/tiles.xml` 을 다시 만든다.
본진 프레임 추출은 `tools/cut_base_frames.py`, 서술자 작성은 `tools/write_atlas_xml.py` 가 맡는다.
배치가 바뀌어도 이름은 그대로라 매니페스트는 손대지 않아도 된다.

---

## 11. 크레딧

CC0 이므로 법적 의무는 없으나 `CREDITS.md` 에 명시한다.

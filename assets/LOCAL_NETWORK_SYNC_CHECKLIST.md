# Battle City: Local Assault — 로컬 네트워크 동기화 동작 체크리스트

이 문서는 스타크래프트의 로컬 네트워크 방처럼 **방 생성 → 참가 → 준비 → 동시 시작 → 실시간 플레이 → 결과 확정 → 재경기/로비 복귀** 흐름을 안정적으로 구현하기 위한 검증 체크리스트다.

이 체크리스트의 주 사용 시점은 **AI 바이브 코딩으로 네트워크 게임 기능이 어느 정도 완성된 후의 통합 점검 단계**다. 새 기능을 설계하는 요구사항 목록으로만 사용하지 말고, 현재 코드·실행 로그·멀티 기기 테스트 결과를 근거로 실제 동작을 감사한다.

게임의 기본 전제는 다음과 같다.

- 동일 Wi-Fi의 2~4인 플레이
- Host authoritative 구조
- 게임 로직 60Hz
- 네트워크 상태 전송 20~30Hz
- Client는 이동·발사·특수기 입력만 전송
- Host가 COM, 충돌, 피해, 사망, 점수, 본진, 승패를 최종 판정
- Client 이탈 시 해당 플레이어를 탈락/연결 끊김 처리하고 게임 지속
- Host 이탈 시 Host Migration 없이 게임 종료 후 로비로 복귀

---

## 0. 완료 후 AI 점검 운영 방법

### 0.1 판정 상태

각 체크 항목은 단순히 체크 표시만 하지 말고 다음 상태 중 하나로 판정한다.

| 상태 | 의미 |
|---|---|
| `PASS` | 코드와 실행 증거로 요구 동작을 확인함 |
| `FAIL` | 요구 동작과 다른 결과 또는 결함을 확인함 |
| `PARTIAL` | 일부 경로만 구현됐거나 특정 조건에서만 통과함 |
| `NOT_VERIFIED` | 구현은 보이지만 실행 증거가 없어 판정할 수 없음 |
| `NOT_IMPLEMENTED` | 관련 구현이 없음 |
| `N/A` | 현재 버전 범위에 해당하지 않음. 사유 필수 |

AI는 코드가 존재한다는 이유만으로 `PASS`를 부여하면 안 된다. 다음 중 최소 하나의 증거가 필요하다.

- 자동화 테스트 이름과 결과
- 재현 가능한 멀티 기기 실행 절차와 결과
- Host/Client 로그의 sessionId, matchId, tick, sequence 기록
- 패킷 캡처 또는 네트워크 시뮬레이션 결과
- 관련 코드 파일과 함수 위치, 그리고 실제 호출 경로

### 0.2 심각도

| 등급 | 기준 | 예시 |
|---|---|---|
| `P0` | 게임 결과 신뢰성 또는 세션 전체가 깨짐 | 서로 다른 맵 시작, 중복 점수, Client 승패 조작, Host 종료 미감지 |
| `P1` | 정상 플레이가 자주 불가능하거나 상태 불일치 | Ready 불일치, 유령 이동, 본진 상태 불일치, 재경기 오염 |
| `P2` | 복구 가능하지만 품질이 크게 저하 | 심한 위치 튐, 늦은 UI 갱신, 부정확한 네트워크 품질 표시 |
| `P3` | 경미한 UX·로그·유지보수 문제 | 문구, 진단 정보 부족, 일시적 표시 지연 |

### 0.3 점검 전에 고정할 정보

```text
AuditContext
 ├─ auditDate
 ├─ gitCommit / buildVersion
 ├─ protocolVersion
 ├─ mapGenerationVersion
 ├─ gameConfigHash
 ├─ Android 기기/OS 목록
 ├─ Host 기기
 ├─ playerCount 조합
 ├─ networkCondition
 └─ tester / AI reviewer
```

- [ ] 점검 대상 commit 또는 빌드를 고정한다.
- [ ] 테스트에 사용한 APK와 서버/Host 코드 버전을 기록한다.
- [ ] 테스트 기기, Android 버전, Wi-Fi 환경을 기록한다.
- [ ] 디버그 로그에서 sessionId, matchId, serverTick을 확인할 수 있게 한다.
- [ ] 네트워크 지연·유실·중복·역순을 주입할 방법을 준비한다.
- [ ] 테스트 전 기존 앱 데이터 유지/초기화 여부를 기록한다.

### 0.4 권장 점검 순서

```text
1. 정적 코드 점검
2. 메시지 스키마와 상태 머신 점검
3. 자동화 테스트 실행
4. 2인 정상 네트워크 실기기 테스트
5. 3~4인 정상 네트워크 테스트
6. 지연·유실·중복·역순 주입 테스트
7. Client/Host 강제 종료 테스트
8. Victory/Game Over/동점/재경기 테스트
9. 로그·checksum으로 결과 교차 검증
10. FAIL/PARTIAL 항목 우선순위 지정
```

### 0.5 항목별 기록 형식

```text
체크 ID: LOBBY-READY-04
상태: PASS | FAIL | PARTIAL | NOT_VERIFIED | NOT_IMPLEMENTED | N/A
심각도: P0 | P1 | P2 | P3
확인 환경: 4 Players / Host Pixel 8 / 100ms RTT / 5% loss
코드 근거: app/network/LobbyState.kt:123
실행 근거: testReadyResetWhenSettingsChanged / session log 2026-08-20-01
관찰 결과: Host 설정 변경 후 4대 모두 ready=false 수신
재현 절차: ...
수정 권장: ...
담당/상태: ...
```

파일 경로와 테스트 이름은 실제 저장소에서 확인한 값만 기록한다. 존재하지 않는 코드·테스트·로그를 추정해서 작성하지 않는다.

체크 ID가 문서에 별도로 부여되지 않은 항목은 `섹션-하위영역-순번` 규칙으로 생성한다. 예: `04-READY-06`, `12-PACKET-03`, `16-RESULT-02`.

---

## 1. 전체 상태 흐름

```text
DISCOVERY
  ↓
JOINING
  ↓
LOBBY
  ↓
READY_CHECK
  ↓
START_PREPARE
  ↓
COUNTDOWN
  ↓
PLAYING
  ↓
ENDING
  ↓
RESULT
  ├─ REMATCH_PREPARE → LOBBY
  └─ RETURN_TO_MENU
```

### 공통 상태 규칙

- [ ] Host와 Client가 동일한 `GamePhase` enum을 사용한다.
- [ ] 모든 네트워크 메시지에 `protocolVersion`이 포함된다.
- [ ] 모든 게임 세션에 고유한 `sessionId`가 있다.
- [ ] 재경기 시 이전 게임과 다른 `matchId`를 발급한다.
- [ ] 모든 플레이어에 세션 동안 변하지 않는 `playerId`가 있다.
- [ ] 메시지에 `sequence`, `serverTick` 또는 둘 다 포함한다.
- [ ] 이전 `sessionId`/`matchId`의 지연 패킷은 폐기한다.
- [ ] 정의되지 않은 상태 전이는 로그를 남기고 무시한다.
- [ ] UI 화면 상태를 로컬 버튼 클릭이 아니라 네트워크 `GamePhase`로 결정한다.

---

## 2. 권장 메시지 분류

### 반드시 도착해야 하는 메시지

유실 시 재전송 또는 신뢰성 있는 채널을 사용한다.

- [ ] `JOIN_REQUEST`
- [ ] `JOIN_ACCEPTED` / `JOIN_REJECTED`
- [ ] `PLAYER_JOINED` / `PLAYER_LEFT`
- [ ] `LOBBY_SETTINGS_CHANGED`
- [ ] `READY_CHANGED`
- [ ] `TANK_SELECTED`
- [ ] `START_PREPARE`
- [ ] `START_READY_ACK`
- [ ] `START_COMMIT`
- [ ] `PLAYER_DIED`
- [ ] `PLAYER_DISCONNECTED`
- [ ] `BASE_DESTROYED`
- [ ] `GAME_ENDED`
- [ ] `RESULT_ACK`
- [ ] `RETURN_TO_LOBBY`

### 최신 값이 중요한 메시지

오래된 메시지를 재전송하기보다 새 상태로 덮어쓴다.

- [ ] `INPUT_COMMAND`
- [ ] `GAME_SNAPSHOT`
- [ ] `PING` / `PONG`
- [ ] `NETWORK_QUALITY`

### 이벤트와 상태를 구분

- [ ] 발사, 폭발, 사망은 중복 재생 방지를 위해 고유 `eventId`를 가진다.
- [ ] HP, 위치, 점수는 스냅샷의 최신 값으로 교정할 수 있다.
- [ ] `GAME_ENDED` 같은 단발 이벤트는 ACK 전까지 재전송한다.
- [ ] 이벤트 메시지를 스냅샷 손실 복구의 유일한 수단으로 사용하지 않는다.

---

## 3. 방 검색 및 생성

### Host

- [ ] 방 생성 시 새로운 `sessionId`를 발급한다.
- [ ] Host 자신을 첫 번째 플레이어 슬롯에 등록한다.
- [ ] 최대 인원을 4명으로 제한한다.
- [ ] 방 검색 응답에 방 이름, Host 주소, 현재 인원, 최대 인원, 버전을 포함한다.
- [ ] 게임 시작 후에는 방 검색 결과를 `IN_GAME` 또는 참가 불가로 표시한다.
- [ ] 중복 방 검색 요청에 동일한 방 정보를 반환한다.
- [ ] 네트워크 인터페이스 변경 시 광고 주소를 갱신한다.

### Client

- [ ] 동일 Wi-Fi에서 방 목록을 검색할 수 있다.
- [ ] 같은 방 응답을 중복 표시하지 않는다.
- [ ] 일정 시간 응답이 없는 방을 목록에서 제거한다.
- [ ] 앱 버전 또는 프로토콜 버전이 다르면 참가 전에 표시한다.
- [ ] 방이 가득 찼거나 이미 시작됐으면 명확한 실패 사유를 표시한다.

### 참가 검증

- [ ] 동일 기기의 중복 참가를 감지한다.
- [ ] `playerId` 충돌 시 새로운 ID를 발급하거나 참가를 거절한다.
- [ ] Host가 슬롯 번호와 플레이어 색상을 최종 할당한다.
- [ ] Client가 임의로 P1~P4 슬롯을 결정할 수 없다.
- [ ] 참가 승인 직후 전체 `LobbyState`를 한 번에 전달한다.
- [ ] 참가 완료 전에 Ready/Start 입력을 허용하지 않는다.

---

## 4. 로비 상태 동기화

### LobbyState 필수 데이터

```text
LobbyState
 ├─ sessionId
 ├─ revision
 ├─ hostPlayerId
 ├─ gamePhase
 ├─ players[]
 │   ├─ playerId
 │   ├─ slot
 │   ├─ displayName
 │   ├─ color
 │   ├─ tankType
 │   ├─ ready
 │   └─ connectionState
 └─ settings
     ├─ mapSize
     ├─ mapMode
     ├─ mapSeed
     ├─ friendlyFire
     ├─ totalEnemyRule
     ├─ maxActiveEnemies
     └─ baseProtection
```

### 플레이어 목록

- [ ] 입장/퇴장 때 Host가 `revision`을 증가시킨다.
- [ ] Client는 더 낮은 revision의 로비 상태를 무시한다.
- [ ] 모든 기기에서 플레이어 순서와 색상이 동일하다.
- [ ] 플레이어 이름 변경이 모든 Client에 반영된다.
- [ ] 연결 끊김, 대기, 준비 상태가 명확히 구분된다.
- [ ] 로비에서 나간 슬롯을 안전하게 재사용할 수 있다.
- [ ] Host 표시는 모든 기기에서 동일한 플레이어에게 붙는다.

### 탱크 선택

- [ ] 공격형·방어형·스피드형 선택을 Host가 검증한다.
- [ ] 동일 탱크 타입의 중복 선택 허용 여부가 설정과 일치한다.
- [ ] 탱크 변경 시 Ready가 자동 해제되는지 정책을 통일한다.
- [ ] 게임 시작 준비 단계 이후 탱크 변경을 거절한다.
- [ ] 모든 Client가 최종 `tankType`을 확인한다.

### Ready 처리

- [ ] Ready 요청은 본인 `playerId`에 대해서만 가능하다.
- [ ] Host가 승인한 Ready 상태만 UI에 확정 표시한다.
- [ ] 참가자 수가 2명 미만이면 시작 버튼이 비활성화된다.
- [ ] 모든 참가자가 Ready인지 Host가 최종 계산한다.
- [ ] 설정 변경 시 모든 Client의 Ready를 해제한다.
- [ ] 플레이어 참가/퇴장 시 Ready 조건을 다시 계산한다.
- [ ] Host만 START GAME을 실행할 수 있다.

### 로비 게임 설정

- [ ] Host만 맵 크기, seed, Friendly Fire, 적 수 등을 변경한다.
- [ ] Client의 설정 변경 요청은 거절한다.
- [ ] 설정 변경이 하나의 revision으로 원자적으로 반영된다.
- [ ] `totalEnemyCount = playerCount × 20`을 Host가 계산한다.
- [ ] `maxActiveEnemies`가 기기/플레이어 수 제한 범위 안인지 검증한다.
- [ ] BGM/SFX 볼륨은 개인 기기 설정이며 LobbyState에 포함하지 않는다.
- [ ] BGM/SFX 음소거가 다른 플레이어에게 전파되지 않는다.

---

## 5. 게임 시작 배리어

START 버튼 클릭 즉시 각 기기에서 독립적으로 게임을 시작하지 않는다.

### Prepare 단계

- [ ] Host가 `matchId`, `mapSeed`, `startServerTime`, 설정 스냅샷을 포함한 `START_PREPARE`를 전송한다.
- [ ] `startServerTime`은 현재보다 충분히 미래다. 권장 2~4초.
- [ ] 모든 Client가 맵을 생성하거나 로드한다.
- [ ] 모든 Client가 맵 해시를 계산한다.
- [ ] 모든 Client가 필수 에셋과 탱크 Config 버전을 확인한다.
- [ ] Client는 준비 완료 후 `START_READY_ACK`를 보낸다.
- [ ] ACK에 `mapHash`, `configHash`, `assetVersion`을 포함한다.

### Commit 단계

- [ ] Host가 모든 Client ACK를 비교한다.
- [ ] 맵/Config 해시가 다르면 시작하지 않고 오류를 표시한다.
- [ ] 제한 시간 내 ACK가 없는 Client를 시작에서 제외하거나 로비로 복귀시킨다.
- [ ] Host가 최종 `START_COMMIT`을 신뢰성 있게 전송한다.
- [ ] 각 기기는 `startServerTime` 기준으로 3-2-1-GO를 표시한다.
- [ ] 렌더링 지연과 관계없이 첫 게임 tick이 동일한 기준 시각에서 시작한다.
- [ ] 카운트다운 중 이동·발사·특수기 입력을 게임 로직에 적용하지 않는다.
- [ ] `START_COMMIT`을 중복 수신해도 게임을 두 번 초기화하지 않는다.

---

## 6. 시간과 Tick 동기화

- [ ] 로비에서 주기적으로 Ping/Pong으로 Host 시각 오프셋을 계산한다.
- [ ] 단일 ping이 아니라 최근 여러 샘플의 중앙값/최솟값을 사용한다.
- [ ] 게임 로직 tick은 60Hz 고정 timestep으로 실행한다.
- [ ] 네트워크 스냅샷은 20~30Hz로 전송한다.
- [ ] 렌더 FPS가 게임 tick에 영향을 주지 않는다.
- [ ] Client는 `serverTick` 기준으로 보간한다.
- [ ] 큰 시간 오차를 한 프레임에 순간 보정하지 않고 서서히 조정한다.
- [ ] 앱이 백그라운드에 갔다 돌아오면 시간 기준을 다시 동기화한다.

---

## 7. 입력 동기화

### Client → Host 입력

```text
InputCommand
 ├─ matchId
 ├─ playerId
 ├─ inputSequence
 ├─ clientTick
 ├─ direction: NONE/UP/RIGHT/DOWN/LEFT
 ├─ firePressed
 └─ specialPressed
```

- [ ] 조이스틱 아날로그 값을 전송하지 않고 최종 4방향 값을 전송한다.
- [ ] 방향 유지 상태와 버튼 눌림 edge를 구분한다.
- [ ] `firePressed`와 `specialPressed`의 중복 패킷이 중복 발사로 이어지지 않는다.
- [ ] `inputSequence`가 이전 값보다 낮거나 같으면 중복/역순 입력으로 처리한다.
- [ ] Host가 플레이어별 마지막 입력 sequence를 기록한다.
- [ ] 입력이 잠시 누락되면 마지막 이동 방향 유지 시간을 제한한다.
- [ ] 일정 시간 새 입력이 없으면 `NONE`으로 전환해 유령 이동을 방지한다.
- [ ] Client가 허용되지 않은 연사·특수기 속도를 요청해도 Host 쿨타임이 차단한다.
- [ ] 사망/탈락/카운트다운 상태의 입력을 Host가 무시한다.

### 로컬 입력 반응

- [ ] 조이스틱 UI는 즉시 반응한다.
- [ ] 탱크 이동은 Client prediction 사용 여부를 명시한다.
- [ ] prediction 사용 시 Host 위치와 오차가 임계값을 넘으면 부드럽게 교정한다.
- [ ] 발사/특수기 시각 효과를 예측 재생할 경우 Host 거절 때 취소할 수 있다.

---

## 8. Host 게임 판정

- [ ] COM 생성 위치와 시각을 Host만 결정한다.
- [ ] COM AI 상태 전이는 Host만 실행한다.
- [ ] 탱크-타일, 탱크-탱크, 포탄 충돌을 Host만 최종 판정한다.
- [ ] 피해량, HP, Life 감소를 Host만 적용한다.
- [ ] 포탄과 탱크에 세션 내 고유 `entityId`가 있다.
- [ ] 파괴된 entity ID를 즉시 재사용하지 않는다.
- [ ] 플레이어 및 적 Respawn을 Host가 결정한다.
- [ ] 점수와 Kill 귀속을 Host가 결정한다.
- [ ] 본진 보호/피해/파괴 상태를 Host가 결정한다.
- [ ] Friendly Fire와 아군의 본진 공격 규칙을 Host가 적용한다.
- [ ] Client가 보낸 HP, 점수, 위치 결과값을 신뢰하지 않는다.

---

## 9. GameSnapshot 동기화

### 권장 데이터

```text
GameSnapshot
 ├─ matchId
 ├─ snapshotSequence
 ├─ serverTick
 ├─ phase
 ├─ players[]
 ├─ enemies[]
 ├─ projectiles[]
 ├─ baseState
 ├─ tileChanges[]
 ├─ scores[]
 ├─ remainingEnemies
 └─ stateChecksum
```

- [ ] 스냅샷에 현재 serverTick이 포함된다.
- [ ] 오래된 snapshotSequence는 폐기한다.
- [ ] 플레이어 위치·방향·HP·Life·생존 여부를 포함한다.
- [ ] 특수기 쿨타임과 활성 상태를 포함한다.
- [ ] 활성 COM의 타입·위치·방향·HP를 포함한다.
- [ ] 활성 포탄의 ID·소유자·위치·방향을 포함한다.
- [ ] 본진 상태와 보호 여부를 포함한다.
- [ ] 남은 총 COM과 현재 활성 COM 수를 구분한다.
- [ ] 전체 타일맵을 매번 전송하지 않고 변경된 타일을 전송한다.
- [ ] 새로 참가한 상태 복구 대상에는 전체 keyframe snapshot을 제공한다.

### 보간과 교정

- [ ] Client가 2~3개의 snapshot을 버퍼링한다.
- [ ] 렌더 위치는 과거 server time 기준으로 보간한다.
- [ ] 순간이동/Respawn/사망은 보간하지 않고 이벤트로 처리한다.
- [ ] 작은 위치 오차는 여러 프레임에 걸쳐 교정한다.
- [ ] 큰 위치 오차는 즉시 Host 상태로 스냅한다.
- [ ] 존재하지 않는 entity를 일정 유예 후 제거한다.
- [ ] 이미 파괴된 entity가 늦은 snapshot으로 다시 나타나지 않게 sequence를 확인한다.

---

## 10. 맵과 환경 상태 동기화

- [ ] 랜덤 맵은 `mapSeed + generationVersion + settings`로 결정된다.
- [ ] Host와 Client가 생성한 맵 해시가 동일하다.
- [ ] 지형 variation은 좌표 기반 hash로 결정해 난수 호출 순서에 의존하지 않는다.
- [ ] BRICK 피해 단계와 파괴 상태를 Host가 전송한다.
- [ ] STEEL 피해 가능 여부와 관통탄 결과를 Host가 판정한다.
- [ ] BASE 상태 전환에 단조 증가하는 revision 또는 tick을 포함한다.
- [ ] WATER 애니메이션 프레임은 전송하지 않고 server time에서 계산한다.
- [ ] FOREST/ICE 같은 정적 타일은 초기 맵 해시에 포함한다.
- [ ] 늦은 타일 파괴 이벤트가 최신 상태를 되돌리지 않는다.

---

## 11. 점수·사망·Respawn

- [ ] 적 사망에 `killerPlayerId`와 `enemyId`가 포함된다.
- [ ] 동일 enemyId에 점수가 두 번 지급되지 않는다.
- [ ] Player HP가 0이면 Host가 Life를 정확히 한 번 감소시킨다.
- [ ] Life가 남으면 Respawn tick과 위치를 Host가 전송한다.
- [ ] Respawn 무적 시간이 모든 기기에서 같은 server tick으로 끝난다.
- [ ] Life가 0이면 `alive=false`, `eliminated=true`로 확정한다.
- [ ] 탈락 플레이어의 입력을 무시한다.
- [ ] HUD의 Kill/Life/HP는 Host 상태만 표시한다.
- [ ] 동시 처치/동시 피격의 처리 순서가 serverTick과 event ordering으로 고정된다.

---

## 12. 지연·유실·중복·역순 처리

- [ ] 0ms, 50ms, 100ms, 200ms, 400ms RTT에서 테스트한다.
- [ ] 1%, 5%, 10%, 20% 패킷 유실에서 테스트한다.
- [ ] ±30ms, ±100ms jitter에서 테스트한다.
- [ ] 패킷 역순 도착을 강제로 발생시킨다.
- [ ] 동일 입력/이벤트 패킷을 중복 전달한다.
- [ ] 오래된 스냅샷이 최신 상태를 덮지 않는다.
- [ ] 유실 시 탱크가 무한히 한 방향으로 이동하지 않는다.
- [ ] 중복 `firePressed`가 두 발을 만들지 않는다.
- [ ] 중복 `GAME_ENDED`가 결과 화면을 두 번 열지 않는다.
- [ ] 네트워크 품질을 HUD에 표시하되 게임 판정에는 사용하지 않는다.

---

## 13. Heartbeat와 연결 끊김

### Client 연결 끊김

- [ ] Host가 주기적 heartbeat 또는 입력 timeout으로 연결을 감지한다.
- [ ] 짧은 패킷 지연과 실제 연결 끊김의 timeout을 구분한다.
- [ ] 권장 상태: `CONNECTED → SUSPECTED → DISCONNECTED`.
- [ ] SUSPECTED 상태를 다른 플레이어 UI에 즉시 탈락으로 표시하지 않는다.
- [ ] timeout 후 Host가 `PLAYER_DISCONNECTED`를 확정한다.
- [ ] 계획서 정책대로 해당 플레이어를 DEAD/DISCONNECTED 처리한다.
- [ ] 남은 플레이어가 게임을 계속할 수 있다.
- [ ] 연결 끊긴 플레이어의 탱크·포탄·특수기 잔여 상태를 정리한다.
- [ ] 총 COM 목표 수를 최초 플레이어 수 기준으로 유지할지 정책을 고정한다.
- [ ] 권장: 게임 시작 시 결정된 총 COM 수를 유지한다.

### Host 연결 끊김

- [ ] 모든 Client가 Host heartbeat timeout을 감지한다.
- [ ] Host Migration을 시도하지 않는다.
- [ ] 입력과 로컬 simulation을 즉시 중지한다.
- [ ] `HOST DISCONNECTED` 사유를 표시한다.
- [ ] 게임 결과를 임의로 승리/패배 처리하지 않는다.
- [ ] 네트워크 객체와 match 상태를 정리한다.
- [ ] 로비 또는 메인 메뉴 복귀 정책이 모든 Client에서 동일하다.

### 재접속

MVP에서 재접속을 지원하지 않는 경우:

- [ ] 게임 중 재접속 요청을 명확히 거절한다.
- [ ] 같은 playerId의 새 연결을 신규 플레이어로 받지 않는다.
- [ ] UI에 현재 게임에는 다시 들어갈 수 없음을 표시한다.

추후 재접속을 지원하는 경우:

- [ ] 재접속 token과 제한 시간을 사용한다.
- [ ] Host가 전체 keyframe snapshot을 전송한다.
- [ ] snapshot 적용 전 입력을 금지한다.
- [ ] 기존 entity 소유권과 playerId를 복구한다.

---

## 14. 앱 생명주기와 기기 상태

- [ ] Client 앱이 백그라운드로 가면 Host에 상태를 알린다.
- [ ] 백그라운드 timeout 정책을 일반 네트워크 끊김과 통일한다.
- [ ] 화면 회전/폴더블 전환이 네트워크 세션을 재생성하지 않는다.
- [ ] Wi-Fi가 잠시 끊겼다 복구될 때 중복 socket/session을 만들지 않는다.
- [ ] Wi-Fi에서 모바일 데이터로 전환되면 LAN 세션 종료를 처리한다.
- [ ] 화면 잠금 후 복귀 시 server time과 snapshot을 다시 동기화한다.
- [ ] 저사양 기기의 렌더 FPS 저하가 heartbeat를 막지 않는다.
- [ ] 네트워크 송수신 스레드가 렌더링 스레드에 종속되지 않는다.

---

## 15. 게임 종료 판정

### Victory

- [ ] `remainingEnemies == 0`을 Host만 판정한다.
- [ ] 마지막 적의 사망 점수가 반영된 뒤 결과를 계산한다.
- [ ] 플레이어별 Kill Count를 비교한다.
- [ ] 동점 시 Life → HP → 공동 승리 순서를 적용한다.

### Game Over

- [ ] 본진 파괴를 Host만 확정한다.
- [ ] 아군 탄환의 본진 파괴도 동일하게 처리한다.
- [ ] 모든 플레이어 Life 소진을 Host만 확정한다.
- [ ] Victory와 Game Over가 같은 tick에 발생할 때 우선순위를 정의한다.
- [ ] 권장: 본진 파괴 또는 전원 탈락이 발생한 경우 Game Over 우선.

### 종료 원자성

- [ ] Host가 `PLAYING → ENDING`을 한 번만 전환한다.
- [ ] ENDING 이후 신규 입력, COM Spawn, 점수 변경을 차단한다.
- [ ] 이미 진행 중인 판정 중 결과에 필요한 이벤트만 마무리한다.
- [ ] 최종 결과에 `endReason`, `winnerIds`, `scores`, `endServerTick`을 포함한다.

---

## 16. GAME_ENDED와 결과 화면

```text
GameEnded
 ├─ matchId
 ├─ eventId
 ├─ endServerTick
 ├─ reason: VICTORY/BASE_DESTROYED/ALL_PLAYERS_DEAD/HOST_DISCONNECTED
 ├─ winnerPlayerIds[]
 ├─ finalPlayers[]
 │   ├─ kills
 │   ├─ lives
 │   ├─ hp
 │   └─ rank
 └─ resultHash
```

- [ ] `GAME_ENDED`를 신뢰성 있게 전송한다.
- [ ] Client가 중복 GAME_ENDED를 idempotent하게 처리한다.
- [ ] Client가 결과를 수신하면 `RESULT_ACK`를 보낸다.
- [ ] Host가 일정 시간 동안 미수신 Client에 결과를 재전송한다.
- [ ] 결과 화면 진입 시 게임 렌더/입력을 멈춘다.
- [ ] 모든 기기에서 순위, Kill, Life, HP가 동일하다.
- [ ] `resultHash`가 다르면 진단 로그를 저장한다.
- [ ] Host 연결 끊김 결과에는 승자와 점수를 확정하지 않는다.

---

## 17. 재경기와 로비 복귀

### Return to Lobby

- [ ] Host만 전체 세션의 로비 복귀를 확정한다.
- [ ] 새로운 LobbyState revision을 발급한다.
- [ ] 모든 플레이어 Ready를 false로 초기화한다.
- [ ] 탱크 선택 유지/초기화 정책을 통일한다.
- [ ] 이전 match의 entity, projectile, timer, event queue를 제거한다.
- [ ] 이전 matchId 패킷을 폐기한다.
- [ ] 로비 BGM으로 정상 전환한다.

### Play Again

- [ ] 재경기 요청과 확정을 구분한다.
- [ ] Host가 새 `matchId`를 발급한다.
- [ ] 랜덤 맵이면 새 seed 사용 여부를 설정대로 결정한다.
- [ ] 동일 맵 재경기면 map hash가 이전과 동일한지 확인한다.
- [ ] 결과 화면에서 바로 시작하지 않고 START_PREPARE 배리어를 다시 수행한다.
- [ ] 연결 끊긴 플레이어를 자동 포함하지 않는다.
- [ ] 현재 참가자가 2명 미만이면 재경기를 시작하지 않는다.

---

## 18. 유효성·오류 입력 방어

- [ ] 알려지지 않은 playerId 입력을 무시한다.
- [ ] 다른 플레이어를 가장한 입력을 인증된 연결과 비교한다.
- [ ] 비정상적으로 빠른 입력 전송을 rate limit한다.
- [ ] 유효하지 않은 direction 값을 거절한다.
- [ ] fire/special 쿨타임을 Host에서 검증한다.
- [ ] 맵 경계 밖 위치를 Host가 허용하지 않는다.
- [ ] Client가 점수·HP·승패 메시지를 보낼 수 없게 한다.
- [ ] 과도한 패킷 크기와 entity 개수를 제한한다.
- [ ] 잘못된 메시지 한 건이 네트워크 스레드를 종료시키지 않는다.
- [ ] 오류 사유를 개인정보 없이 진단 로그에 남긴다.

---

## 19. 로그와 Desync 진단

- [ ] 세션 시작/종료 시 sessionId와 matchId를 기록한다.
- [ ] 플레이어 입장·퇴장·Ready·Start 전이를 기록한다.
- [ ] 최근 N초의 입력 sequence와 snapshot sequence를 순환 버퍼에 보관한다.
- [ ] 1~2초 간격으로 핵심 상태 checksum을 비교한다.
- [ ] checksum에는 위치 양자화 값, HP, Life, 점수, 본진, 남은 적 수를 포함한다.
- [ ] 시각 효과, 오디오, 로컬 UI는 checksum에서 제외한다.
- [ ] Desync 발견 시 Host snapshot으로 복구한다.
- [ ] 반복 Desync 시 seed, Config hash, 최근 입력을 파일로 저장한다.
- [ ] 출시 빌드에서는 민감한 네트워크 주소를 마스킹한다.

---

## 20. 필수 테스트 시나리오

### 로비

- [ ] 2명이 생성/참가/Ready/시작한다.
- [ ] 3명과 4명에서도 동일하게 시작한다.
- [ ] 방이 가득 찬 상태에서 5번째 참가를 거절한다.
- [ ] Ready 후 새 플레이어가 참가하면 Ready가 재평가된다.
- [ ] Ready 후 Host가 설정을 바꾸면 Ready가 해제된다.
- [ ] Client가 START를 시도해도 시작되지 않는다.
- [ ] 서로 다른 앱 버전/맵 생성 버전의 참가를 거절한다.
- [ ] 맵 해시가 다른 Client가 있으면 시작되지 않는다.

### 게임 중

- [ ] 4명이 동시에 이동·발사·특수기를 사용한다.
- [ ] 같은 COM을 여러 플레이어가 같은 tick에 공격한다.
- [ ] 같은 tick에 여러 포탄이 같은 BRICK을 파괴한다.
- [ ] 아군이 본진을 파괴해 즉시 Game Over가 된다.
- [ ] 플레이어가 사망하고 Life 감소 후 Respawn한다.
- [ ] 한 플레이어가 모든 Life를 잃어도 나머지가 계속한다.
- [ ] 총 80 COM과 최대 동시 8~12 COM 규칙이 유지된다.
- [ ] 20% packet loss에서도 점수와 최종 결과가 일치한다.

### 연결 끊김

- [ ] Client의 Wi-Fi를 게임 중 끈다.
- [ ] Client 앱을 강제 종료한다.
- [ ] Client 앱을 30초 이상 백그라운드에 둔다.
- [ ] Host Wi-Fi를 끈다.
- [ ] Host 앱을 강제 종료한다.
- [ ] Host 끊김 시 모든 Client가 동일 화면으로 복귀한다.

### 종료

- [ ] 마지막 COM 처치 직후 정확한 Kill이 결과에 반영된다.
- [ ] 본진 파괴와 마지막 COM 처치가 같은 tick에 발생한다.
- [ ] 모든 플레이어가 같은 tick에 사망한다.
- [ ] Kill 동점 후 Life로 승자를 결정한다.
- [ ] Kill/Life 동점 후 HP로 승자를 결정한다.
- [ ] 모든 조건 동점 시 공동 승리를 표시한다.
- [ ] 결과 메시지를 중복 수신해도 결과 화면이 한 번만 열린다.
- [ ] PLAY AGAIN 후 이전 포탄/점수가 남지 않는다.

---

## 21. 네트워크 환경 테스트 매트릭스

| 인원 | Host 기기 | Client 조건 | 지연/유실 | 필수 결과 |
|---:|---|---|---|---|
| 2 | 고성능 Phone | 고성능 Phone | 정상 | 기준 동작 |
| 2 | 저사양 Phone | Tablet | 100ms/5% | 판정 일치 |
| 3 | Tablet | Phone 2대 | jitter 100ms | 시작/결과 일치 |
| 4 | Phone | Phone/Tablet/Foldable | 200ms/10% | 플레이 지속 가능 |
| 4 | 저사양 Phone | 혼합 기기 | 400ms/20% | 오류 없이 보정/종료 |

- [ ] Android 버전과 제조사가 다른 기기 조합을 포함한다.
- [ ] 2.4GHz 및 5GHz Wi-Fi에서 테스트한다.
- [ ] 공유기 AP isolation 환경에서 검색 실패 안내를 확인한다.
- [ ] 폴더블 펼침/접힘 중 세션 유지 여부를 확인한다.

---

## 22. 출시 승인 기준

다음 항목이 모두 통과해야 네트워크 플레이 기능을 출시 가능 상태로 판단한다.

- [ ] 2~4인 로비 상태가 100회 반복 테스트에서 일치한다.
- [ ] START_PREPARE/COMMIT 과정에서 서로 다른 맵으로 시작하지 않는다.
- [ ] 10% 패킷 유실에서 30분 플레이 후 점수·HP·본진 상태가 일치한다.
- [ ] 동일 entity에 중복 Kill/점수가 발생하지 않는다.
- [ ] Client가 임의로 피해·점수·승패를 결정할 수 없다.
- [ ] Client 이탈 후 나머지 플레이어가 게임을 완료할 수 있다.
- [ ] Host 이탈 시 모든 Client가 제한 시간 내 동일하게 종료 처리된다.
- [ ] 모든 Victory/Game Over 조건에서 결과 화면 데이터가 일치한다.
- [ ] 재경기 후 이전 match 상태와 패킷이 완전히 제거된다.
- [ ] Desync 진단에 필요한 session/match/tick/hash 로그가 남는다.

---

## 23. 구현 완료 Definition of Done

```text
[ ] 로비 입장/퇴장/Ready/설정이 Host 기준으로 일치한다.
[ ] 모든 Client가 동일 map/config hash로 동시에 시작한다.
[ ] 입력 sequence와 snapshot sequence가 역순/중복을 방어한다.
[ ] Host만 충돌·피해·점수·본진·승패를 확정한다.
[ ] Client는 보간/예측 후 Host 상태로 안전하게 교정된다.
[ ] 맵 파괴와 entity 상태가 모든 기기에서 일치한다.
[ ] Client/Host 연결 끊김 정책이 계획대로 동작한다.
[ ] GAME_ENDED가 신뢰성 있게 전달되고 결과가 동일하다.
[ ] 로비 복귀/재경기 시 새 matchId와 시작 배리어를 사용한다.
[ ] 지연·유실·중복·역순·백그라운드 테스트를 통과한다.
```

---

## 24. 완료 후 점검 결과 요약 양식

```markdown
# Local Network Sync Audit Result

## 대상
- Commit/Build:
- Protocol version:
- Map generation version:
- 점검 날짜:
- 테스트 기기:

## 판정 요약
| 영역 | PASS | FAIL | PARTIAL | NOT_VERIFIED | N/A |
|---|---:|---:|---:|---:|---:|
| 로비 | | | | | |
| 시작 배리어 | | | | | |
| 게임 중 입력/상태 | | | | | |
| 연결 끊김 | | | | | |
| 게임 종료 | | | | | |
| 재경기/정리 | | | | | |

## 출시 차단 항목
| ID | 심각도 | 문제 | 재현 조건 | 증거 | 권장 수정 |
|---|---|---|---|---|---|

## 미검증 항목
| ID | 미검증 사유 | 필요한 환경/증거 |
|---|---|---|

## 최종 의견
- Release ready: YES / NO / CONDITIONAL
- 필수 수정:
- 후속 테스트:
```

### 출시 판정 규칙

- `P0 FAIL`이 하나라도 있으면 출시 불가
- `P1 FAIL`이 남아 있으면 원칙적으로 출시 불가
- 핵심 영역의 `NOT_VERIFIED`는 PASS로 간주하지 않음
- 정상 네트워크 테스트만 통과하고 장애 주입 테스트가 없으면 `CONDITIONAL`
- 모든 결과는 동일 build에서 다시 회귀 테스트한 뒤 확정

---

## 25. AI 점검 실행 프롬프트

```text
현재 저장소의 로컬 네트워크 게임 구현을
LOCAL_NETWORK_SYNC_CHECKLIST.md 기준으로 완료 후 감사한다.

목표:
- 새로운 기능을 임의로 구현하지 않는다.
- 현재 구현을 정적 분석하고 가능한 테스트를 실행한다.
- 증거 없는 항목은 PASS가 아니라 NOT_VERIFIED로 기록한다.
- 존재하지 않는 파일, 함수, 로그, 테스트 결과를 추정하지 않는다.

점검 순서:
1. 네트워크 상태 머신과 메시지 스키마를 찾는다.
2. Host-authoritative 경계가 실제로 유지되는지 확인한다.
3. 로비, 시작, 게임 중, 연결 끊김, 종료, 재경기 흐름을 추적한다.
4. 관련 Unit/Integration/UI 테스트를 실행한다.
5. 실행할 수 없는 멀티 기기 항목은 필요한 수동 테스트 절차를 작성한다.
6. 각 항목에 PASS/FAIL/PARTIAL/NOT_VERIFIED/NOT_IMPLEMENTED/N/A를 부여한다.
7. FAIL에는 P0~P3 심각도, 코드 위치, 재현 절차, 영향, 최소 수정 방향을 기록한다.
8. P0/P1과 출시 차단 항목을 먼저 요약한다.

출력:
- 24절의 완료 후 점검 결과 요약 양식을 사용한다.
- 코드 근거는 실제 파일 경로와 줄 번호를 포함한다.
- 실행한 명령과 테스트 결과를 기록한다.
- 코드 변경은 별도 요청이 없으면 수행하지 않는다.
```

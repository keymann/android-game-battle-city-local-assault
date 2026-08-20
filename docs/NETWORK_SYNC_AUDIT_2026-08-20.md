# Local Network Sync Audit Result

`assets/LOCAL_NETWORK_SYNC_CHECKLIST.md` §25 지침에 따라 감사만 수행했다. 코드는 고치지 않았다.

## 1. 점검 대상

| 항목 | 값 |
|---|---|
| Repository | android-game-battle-city-local-assault |
| Commit / Build | `4bca125` (2026-08-20 09:28 +0900) |
| APK version | `release/battle-city-local-assault-release.apk` (R8 적용, 디버그 키 서명) |
| Protocol version | `Protocol.VERSION = 1` (`net/Protocol.kt:26`) |
| Map generation version | `manifest/mapgen.json` v1 (`StageGenerator`), 매니페스트 v3 |
| Game config hash | 없음. 설정 해시를 계산하거나 교환하는 코드가 없다 |
| 점검 날짜 | 2026-08-20 |
| AI reviewer | Claude Opus 5 (정적 분석 + 단위/통합 테스트 실행) |

## 2. 테스트 환경

| 역할 | 기기 | Android | 네트워크 조건 | 비고 |
|---|---|---|---|---|
| Host | Pixel 9 Pro 에뮬레이터 | API 36 | 에뮬레이터 내부 | 단독 실행만 확인 |
| Client 1 | 없음 | — | — | **실기기 2대 이상 테스트를 수행하지 못했다** |
| Client 2 | 없음 | — | — | — |
| Client 3 | 없음 | — | — | — |

에뮬레이터 두 대는 각자 NAT 뒤에 있어 UDP 브로드캐스트가 오가지 않는다. 실제 Host-Client
연결은 이번 감사에서 한 번도 성립하지 않았다. 이 사실이 아래 판정 대부분을 `NOT_VERIFIED`로
만든다.

## 3. 실행 증거

실행한 명령과 결과는 다음과 같다.

```
./gradlew :app:testDebugUnitTest --rerun-tasks   → BUILD SUCCESSFUL
총 414건, 실패 0, 건너뜀 0 (테스트 클래스 31개)
```

Host-Client 동작을 다루는 테스트는 세 개다. 모두 `LoopbackTransport`로 메모리 안에서
패킷을 주고받고, 시계를 손으로 돌린다.

| 테스트 클래스 | 건수 | 다루는 범위 |
|---|---:|---|
| `net/SessionTest` | 20 | 방 검색, 입장, 정원 초과 거절, 준비, START, 타임아웃 |
| `net/Phase9SessionTest` | 13 | RTT 측정, 결과 화면 재석, 로비 재개, PLAY AGAIN, 방 규칙 전달 |
| `game/LobbyFlowTest` | 13 | 참가자 선택 전달, 준비, 시작 조건, 카운트다운 전파, 퇴장 |

단일 기기 실행 증거는 `screen=MENU/ROOMS/LOBBY/BATTLE/RESULT` 로그와 60fps 기록뿐이다.
Host 로그, Client 로그, 패킷 캡처, 지연·유실 주입 결과는 **없다**.

## 4. 영역별 판정

| 영역 | PASS | FAIL | PARTIAL | NOT_VERIFIED | NOT_IMPLEMENTED | N/A |
|---|---:|---:|---:|---:|---:|---:|
| 방 검색/참가 | 9 | 0 | 2 | 4 | 3 | 0 |
| 로비 상태/Ready | 11 | 2 | 3 | 4 | 6 | 0 |
| 게임 시작 배리어 | 2 | 2 | 3 | 2 | 6 | 0 |
| Tick/입력 동기화 | 7 | 3 | 2 | 4 | 5 | 0 |
| Snapshot/보간 | 8 | 2 | 3 | 3 | 5 | 0 |
| 맵/환경 상태 | 4 | 2 | 0 | 1 | 2 | 0 |
| 피해/점수/Respawn | 7 | 0 | 1 | 2 | 0 | 0 |
| 지연/유실/중복 | 1 | 2 | 0 | 7 | 0 | 0 |
| 연결 끊김 | 5 | 3 | 2 | 5 | 5 | 2 |
| Victory/Game Over | 9 | 0 | 2 | 1 | 2 | 0 |
| 결과/재경기 | 8 | 0 | 3 | 2 | 3 | 0 |

## 5. 출시 차단 Findings

| ID | 상태 | 심각도 | 문제 | 코드 근거 | 실행 근거 | 재현 절차 | 최소 수정 방향 |
|---|---|---|---|---|---|---|---|
| `13-HOST-03` | FAIL | **P0** | Host가 끊기면 Client가 전투 화면에 갇힌다. 소리만 멈추고 화면·입력·상태가 그대로 남는다. 전투 화면에는 나가는 단추가 없어 앱을 죽여야 빠져나온다 | `game/GameHost.kt:490` (`onDisconnected` 가 `stopAllLoops` + 효과음만 실행), `net/ClientSession.kt:224` (HOST_CLOSED → disconnect) | 없음 (2대 필요) | Client 참가 → 게임 시작 → Host 앱 강제 종료 | `onDisconnected` 에서 `HOST DISCONNECTED` 표시 후 메인 메뉴로 복귀. 승패는 확정하지 않는다 |
| `10-MAP-04` | FAIL | **P1** | 타일 파괴가 Client에 전달되지 않는다. 스냅샷에 `tileChanges`가 없고 Client는 판정을 굴리지 않으므로, 부서진 벽이 Client 화면에 그대로 남는다 | `net/Messages.kt:277-285` (Snapshot 필드), `net/SnapshotBridge.kt:61-90` (`world.map` 을 건드리지 않음), `gameplay/GameWorld.kt:498` (`damageCell` 은 Host 경로에서만 호출) | 없음 (2대 필요) | Client로 참가 → Host가 벽돌 파괴 → Client 화면 확인 | 스냅샷에 변경 셀 목록을 싣거나 파괴 이벤트를 재전송한다 |
| `10-BASE-06` | FAIL | **P1** | 본진 파괴와 보호막 소모가 Client에 반영되지 않는다. `Snapshot.baseDestroyed` 를 부호화·복호화하지만 적용하는 코드가 없다 | `net/Messages.kt:281`·`320` (필드 존재), `net/SnapshotBridge.kt:61-90` (적용 없음), `map/TileMap.kt:25`·`34` | 없음 (2대 필요) | Client 참가 → Host 쪽에서 본진 파괴 → Client 화면 확인 | `SnapshotBridge.apply` 에서 `world.map` 상태를 스냅샷 값으로 맞춘다 |
| `05-COMMIT-02` | FAIL | **P1** | 맵 해시 검증이 동작하지 않는다. Host는 **이전 스테이지**의 해시를 START에 싣고, Client는 **새 스테이지**를 만든 뒤 그 값과 비교한다. 항상 어긋나며 로그만 남기고 게임은 그대로 시작한다 | `game/NetDriver.kt:180` (`prepareStart` 가 현재 로드된 스테이지 해시를 담는다), `game/NetDriver.kt:363-371` (`beginWithProfiles` 뒤에 비교, 불일치 시 `:370` `Log.e` 만) | 없음 | Host·Client 연결 후 첫 판 시작 → Client logcat `맵이 어긋났다` 확인 | 새 스테이지를 만든 뒤 해시를 담고, 불일치 시 시작을 막는다 |
| `13-CLIENT-08` | FAIL | **P1** | 끊긴 플레이어의 탱크가 필드에 그대로 선다. 슬롯만 비우고 탱크·포탄을 정리하지 않으며 AI도 붙지 않아, 움직이지 않는 표적이 남는다 | `game/NetDriver.kt:311-314` (`onPlayerLeft` 가 `humanSlots` 만 정리), `game/BattleScene.kt:181` (AI는 스폰 시점에만 붙는다) | 없음 (2대 필요) | 3인 게임 중 Client 1대 Wi-Fi 차단 → 4초 대기 | 끊긴 슬롯을 탈락 처리하거나 탱크를 제거한다 |
| `07-INPUT-07` | FAIL | **P1** | 입력이 끊겨도 탱크가 계속 이동한다. Host는 새 입력이 올 때만 `moving` 을 바꾸므로, 패킷이 멈추면 타임아웃 4초까지 유령 이동이 이어진다 | `game/BattleScene.kt:199-207` (`applyRemoteInput`), `net/Protocol.kt:48` (`TIMEOUT_MS = 4000`) | 없음 (유실 주입 필요) | Client 이동 중 Wi-Fi 차단 → Host 화면 관찰 | 마지막 입력 이후 일정 시간이 지나면 `NONE` 으로 되돌린다 |
| `04-SETTINGS-05` | FAIL | **P1** | Host가 방 규칙을 바꿔도 참가자 Ready가 풀리지 않는다. 설정은 곧바로 로비 현황에 실려 내려가지만 준비 상태는 그대로다 | `game/NetDriver.kt:154-160` (`roomSettings` setter 가 `prepareStart` 만 호출), `net/LobbyState.kt:93` (`setReady` 는 별도 경로) | `LobbyFlowTest` 에 해당 시나리오 없음 | 참가자 Ready → Host가 본진 보호 토글 → 참가자 Ready 유지 확인 | 설정 변경 시 참가자 Ready를 모두 해제한다 |
| `09-SNAP-02` | FAIL | **P2** | 오래된 스냅샷을 버리지 않는다. 수신 순서대로 덮어쓰므로 역순 도착이 위치를 되돌린다 | `net/ClientSession.kt:201-207` (`tick` 비교 없이 `latest` 교체) | 없음 (역순 주입 필요) | 역순 패킷 주입 후 Client 위치 관찰 | `latest.tick` 보다 낮은 스냅샷을 버린다 |
| `05-COMMIT-08` | FAIL | **P2** | START를 중복 수신하면 스테이지를 다시 만든다. 멱등 처리가 없다 | `game/NetDriver.kt:363-366` (`beginWithProfiles` 무조건 실행) | 없음 (중복 주입 필요) | START 패킷 중복 전달 | 같은 `matchId`(또는 seed+stageIndex) START를 두 번째부터 무시한다 |
| `14-LIFE-08` | FAIL | **P2** | 네트워크 송수신이 게임 루프 스레드에 묶여 있다. 루프가 멈추면 하트비트도 멈춘다 | `game/GameHost.kt:577`·`586`·`593` (`onUpdate` 안에서 `netDriver?.onTick()`), `game/GameHost.kt:145` (`onPause` 가 루프 정지) | 단일 기기 로그 | 앱을 백그라운드로 → 4초 뒤 Host가 타임아웃 처리 | 송수신을 별도 스레드로 분리하거나 일시정지 중에도 하트비트를 보낸다 |

## 6. 미검증 항목

| ID | 미검증 사유 | 필요한 기기/환경 | 필요한 증거 |
|---|---|---|---|
| `12-*` 전 항목 | 지연·유실·중복·역순 주입 수단이 없다 | 실기기 2대 이상, 네트워크 셰이핑 도구 | RTT 0/50/100/200/400ms, 유실 1/5/10/20% 각 조건의 결과 일치 로그 |
| `20-LOBBY-02` 3~4인 시작 | 기기 부족 | 실기기 4대 | 4대 동시 시작 로그 |
| `20-GAME-*` 동시 판정 | 기기 부족 | 실기기 2대 이상 | 같은 tick 다중 피격·파괴 로그 |
| `20-DISC-*` 연결 끊김 | 기기 부족 | 실기기 2대 이상 | Wi-Fi 차단·강제 종료·백그라운드 30초 결과 |
| `06-TICK-*` 시각 동기 | Host-Client 연결 미성립 | 실기기 2대 이상 | 양쪽 tick·오프셋 로그 |
| `09-SNAP-*` 보간 품질 | Host-Client 연결 미성립 | 실기기 2대 이상 | Client 렌더 위치와 Host 위치 비교 |
| `21-*` 환경 매트릭스 | 기기 부족 | 저사양 Phone, Tablet, Foldable | 매트릭스별 결과 |

## 7. 회귀 테스트

이번 감사에서 코드를 고치지 않았다. 회귀 테스트 대상이 없다.

| 수정 항목 | 수정 commit | 재실행 테스트 | 결과 | 증거 |
|---|---|---|---|---|
| — | — | — | — | — |

## 8. 최종 판정

- Release ready: **NO**
- P0 미해결 수: **1** (`13-HOST-03`)
- P1 미해결 수: **6** (`10-MAP-04`, `10-BASE-06`, `05-COMMIT-02`, `13-CLIENT-08`, `07-INPUT-07`, `04-SETTINGS-05`)
- 필수 수정: 위 P0·P1 일곱 건
- 후속 수동 테스트: 실기기 2대 연결부터 다시 시작한다. 연결이 성립해야 §12·§20·§21을 판정할 수 있다
- 최종 근거: 체크리스트 §24 출시 판정 규칙에 따라 P0 FAIL 한 건만으로 출시 불가다. 여기에 더해
  장애 주입 테스트를 한 번도 수행하지 못해 `CONDITIONAL` 조건도 충족하지 못한다

---

# 부록 A. 체크리스트 항목별 판정

판정 근거는 실제 파일과 줄 번호, 그리고 실행한 테스트 이름만 적었다. 추정한 경로나
존재하지 않는 테스트는 쓰지 않았다.

## §1 전체 상태 흐름

| 항목 | 판정 | 근거 |
|---|---|---|
| Host와 Client가 동일 `GamePhase` 사용 | PARTIAL | 화면 상태는 `GameHost.Screen`(`game/GameHost.kt:51`), 판 상태는 `MatchState.Phase`(`gameplay/MatchState.kt:17`)로 나뉜다. 네트워크로 오가는 것은 `MatchState.Phase` 뿐이다 |
| 모든 메시지에 `protocolVersion` 포함 | PASS | `net/Protocol.kt:127` `header()` 가 모든 패킷에 VERSION을 쓰고 `readType()` 이 검사한다 |
| 세션마다 고유 `sessionId` | NOT_IMPLEMENTED | 저장소 전체에 `sessionId` 개념이 없다 |
| 재경기 시 새 `matchId` | NOT_IMPLEMENTED | `matchId` 가 없다. 재경기는 `stageIndex` 만 올린다 (`game/NetDriver.kt:238`) |
| 변하지 않는 `playerId` | NOT_IMPLEMENTED | 슬롯 번호가 곧 신원이다. 슬롯은 재사용된다 (`net/LobbyState.kt:64`) |
| 메시지에 `sequence` 또는 `serverTick` | PARTIAL | 스냅샷과 입력에 `tick` 이 있다 (`net/Messages.kt:222`·`278`). 로비·시작·준비 메시지에는 없다 |
| 이전 세션/매치의 지연 패킷 폐기 | NOT_IMPLEMENTED | 식별자가 없어 구분할 수 없다. 재경기 직후 이전 판 스냅샷이 새 판 entity에 적용될 수 있다 |
| 정의되지 않은 상태 전이는 로그 후 무시 | PARTIAL | `readType` 이 모르는 패킷을 버린다 (`net/Protocol.kt:135`). 로그는 남기지 않는다 |
| 화면 상태를 네트워크 `GamePhase` 로 결정 | PASS | 전투 진입은 세션 신호를 따른다 (`game/NetDriver.kt:365` → `GameHost.enterBattle`) |

## §2 메시지 분류

| 항목 | 판정 | 근거 |
|---|---|---|
| `JOIN_REQUEST` 신뢰 전송 | PASS | 400ms 재전송 (`net/ClientSession.kt:137`, `:267`) |
| `JOIN_ACCEPTED` / `JOIN_REJECTED` | PARTIAL | JOIN 재전송에 응답이 다시 오지만 ACK 기반은 아니다 (`net/HostSession.kt:211-231`) |
| `PLAYER_JOINED` / `PLAYER_LEFT` | PARTIAL | 전용 메시지가 없다. 500ms 주기 LOBBY 전체 상태로 대신한다 (`net/HostSession.kt:239`) |
| `LOBBY_SETTINGS_CHANGED` | PARTIAL | 전용 메시지 없이 LOBBY에 규칙을 실어 보낸다 (`net/HostSession.kt:243-250`) |
| `READY_CHANGED` | PARTIAL | Client→Host는 READY 단발 전송, 확정은 LOBBY 주기 전파에 기댄다 |
| `TANK_SELECTED` | PARTIAL | READY 메시지에 실어 보낸다 (`net/Messages.kt` `writeReady`) |
| `START_PREPARE` | NOT_IMPLEMENTED | 준비 단계가 없다 |
| `START_READY_ACK` | NOT_IMPLEMENTED | — |
| `START_COMMIT` | PARTIAL | `START` 한 통을 한 번만 보낸다 (`net/HostSession.kt:105`). 재전송·ACK가 없다 |
| `PLAYER_DIED` | NOT_IMPLEMENTED | 스냅샷의 점수·생존 필드로만 전달한다 |
| `PLAYER_DISCONNECTED` | NOT_IMPLEMENTED | LOBBY의 `connected` 플래그로만 전달한다 |
| `BASE_DESTROYED` | FAIL | 스냅샷 필드는 있으나 Client가 적용하지 않는다 (`10-BASE-06`) |
| `GAME_ENDED` | PARTIAL | 스냅샷 `phase` 로 전달한다. 20Hz로 반복되므로 사실상 전달되지만 ACK는 없다 |
| `RESULT_ACK` | NOT_IMPLEMENTED | — |
| `RETURN_TO_LOBBY` | PARTIAL | Host가 로비를 다시 열고 LOBBY 전파로 알린다 (`net/HostSession.kt:106-117`) |
| `INPUT_COMMAND` 최신값 채널 | PASS | 매 틱 전송, 재전송 없음 (`game/GameHost.kt:614`) |
| `GAME_SNAPSHOT` 최신값 채널 | PASS | 20Hz 전송 (`game/NetDriver.kt:436`, `net/Protocol.kt:43`) |
| `PING`/`PONG` | PASS | `net/Protocol.kt:113-116`, `net/ClientSession.kt:211-222`. `Phase9SessionTest` 왕복 시간 측정 통과 |
| `NETWORK_QUALITY` | PARTIAL | 별도 메시지 없이 각자 RTT를 재서 HUD에 표시한다 (`render/HudRenderer.kt`) |
| 이벤트에 고유 `eventId` | NOT_IMPLEMENTED | 발사·폭발·사망에 식별자가 없다 |
| HP·위치·점수를 스냅샷으로 교정 | PASS | `net/SnapshotBridge.kt:70-82`, `gameplay/MatchState.kt:204` |
| 단발 이벤트 ACK까지 재전송 | NOT_IMPLEMENTED | — |
| 이벤트를 손실 복구 수단으로 쓰지 않음 | PASS | 상태는 전부 스냅샷에서 온다 |

## §3 방 검색 및 생성

| 항목 | 판정 | 근거 |
|---|---|---|
| 방 생성 시 새 `sessionId` | NOT_IMPLEMENTED | 없음 |
| Host를 첫 슬롯에 등록 | PASS | `net/LobbyState.kt:50-63` `openAsHost`. `SessionTest` `호스트는 언제나 0번이고 준비된 상태다` |
| 최대 4명 제한 | PASS | `net/Protocol.kt:63`, `SessionTest` `방이 차면 더 받지 않는다` |
| 검색 응답에 이름·주소·인원·최대·버전 | PARTIAL | 이름·인원·최대·연 시각은 있다 (`net/Messages.kt:13-27`). 버전 필드는 없다. 주소는 패킷 출처로 얻는다 |
| 시작 후 참가 불가 표시 | PASS | 시작하면 브로드캐스트를 멈추고(`net/HostSession.kt:255`), DISCOVER 응답에는 `started=true` 를 실어 목록에서 지운다 (`net/RoomScanner.kt:118-124`). `RoomScannerTest` `이미 시작한 방은 세지 않는다` |
| 중복 검색에 동일 정보 반환 | PASS | `announcement()` 가 같은 값을 만든다 (`net/HostSession.kt:280-286`) |
| 인터페이스 변경 시 광고 주소 갱신 | NOT_VERIFIED | 소켓을 다시 열지 않는다. Wi-Fi 재연결 시나리오를 실행하지 못했다 |
| 동일 Wi-Fi에서 방 목록 검색 | NOT_VERIFIED | `RoomScanner` 는 구현했고 `RoomScannerTest` 9건이 통과한다. 실기기 브로드캐스트는 확인하지 못했다 |
| 같은 방 중복 표시 안 함 | PASS | 주소를 키로 쓴다 (`net/RoomScanner.kt:40`) |
| 응답 없는 방 제거 | PASS | 3초 (`net/RoomScanner.kt:73`, `net/RoomScanner.kt:130`). `RoomScannerTest` `방이 닫히면 목록에서 사라진다` |
| 버전 불일치 표시 | NOT_IMPLEMENTED | 버전이 다른 패킷은 `readType` 이 조용히 버린다 (`net/Protocol.kt:135-141`). 목록에 나타나지 않을 뿐 사유를 알리지 않는다 |
| 정원 초과·시작됨 사유 표시 | PASS | `game/GameHost.kt:385`·`391` 이 `ROOM IS FULL` / `GAME ALREADY STARTED` 를 목록에 띄운다 |
| 동일 기기 중복 참가 감지 | PASS | 같은 주소면 기존 슬롯을 돌려준다 (`net/HostSession.kt:211-214`) |
| `playerId` 충돌 처리 | N/A | `playerId` 가 없다 |
| Host가 슬롯·색 최종 할당 | PASS | `net/LobbyState.kt:64-76`. `LobbyProfileTest` |
| Client가 슬롯을 정할 수 없음 | PASS | JOIN에 슬롯 필드가 없다 (`net/Messages.kt:37`) |
| 승인 직후 전체 LobbyState 전달 | PASS | `net/HostSession.kt:230` 이 `broadcastLobby(force = true)` 를 호출한다 |
| 참가 완료 전 Ready/Start 금지 | PASS | `slotOf(from)` 이 없으면 READY를 버린다 (`net/HostSession.kt:169`) |

## §4 로비 상태 동기화

| 항목 | 판정 | 근거 |
|---|---|---|
| `revision` 증가 | NOT_IMPLEMENTED | LobbyUpdate에 revision이 없다 (`net/Messages.kt:70-92`) |
| 낮은 revision 무시 | NOT_IMPLEMENTED | 위와 같다. 늦게 온 로비 상태가 최신을 덮을 수 있다 |
| 모든 기기에서 순서·색 동일 | PASS | 슬롯 배열을 그대로 보낸다. `LobbyProfileTest` 색 중복 금지 |
| 이름 변경 전파 | PASS | `LobbyFlowTest` `참가자가 고친 이름이 방장에게 닿는다` |
| 연결·대기·준비 구분 | PASS | `connected`·`ready` 플래그 (`net/Messages.kt:70-78`) |
| 나간 슬롯 재사용 | PASS | `net/LobbyState.kt:64-66` `join` 이 빈 슬롯을 찾는다 |
| Host 표시가 모든 기기에서 동일 | PASS | `host` 플래그를 그대로 전파한다 |
| 탱크 선택을 Host가 검증 | PARTIAL | 범위 검사가 없다. `Tank.Type.entries.getOrElse` 로 읽는 쪽에서 막는다 (`game/NetDriver.kt:415`) |
| 동일 탱크 중복 허용 정책 일치 | PASS | 제한하지 않는 정책이고 코드도 제한하지 않는다 |
| 탱크 변경 시 Ready 해제 | PASS | `game/NetDriver.kt:88-92` 가 `ready=false` 로 발행한다 |
| 준비 단계 이후 탱크 변경 거절 | PASS | `lobby.started` 면 READY를 받아도 카운트다운이 이미 끝난 상태다. 시작 후 화면이 전투로 바뀌어 입력 경로가 사라진다 (`game/GameHost.kt:586`) |
| 모든 Client가 최종 tankType 확인 | PASS | LOBBY 전파 (`LobbyFlowTest` `참가자가 고른 탱크가 방장에게 닿는다`) |
| Ready는 본인 것만 | PASS | 슬롯을 패킷 출처로 정한다 (`net/HostSession.kt:169`) |
| Host 승인분만 UI 확정 | PASS | 화면은 `view.slots` 만 읽는다 (`game/LobbyScene.kt:410-411`) |
| 2명 미만이면 시작 불가 | PASS | `net/LobbyState.kt:149-153`, `LobbyFlowTest` `준비되지 않은 사람이 있으면 시작할 수 없다` |
| Host가 최종 계산 | PASS | `net/LobbyState.kt:149-153` `canStart` |
| 설정 변경 시 Ready 해제 | **FAIL (P1)** | `04-SETTINGS-05` |
| 참가·퇴장 시 Ready 재계산 | PASS | `canStart` 를 매 프레임 계산한다. 퇴장 시 `leave()` 가 카운트다운도 멈춘다 (`net/LobbyState.kt:78-84`) |
| Host만 START 실행 | PASS | `game/LobbyScene.kt:600-604` `bottomAction`, `LobbyFlowTest` `방장에게는 시작 단추다` |
| Host만 설정 변경 | PASS | Client에는 설정 변경 메시지가 없다. 설정 화면은 방장만 편집한다 (`game/GameHost.kt:404`) |
| Client 설정 변경 거절 | PASS | 위와 같다. 프로토콜에 경로가 없다 |
| 설정을 하나의 revision으로 원자 반영 | PARTIAL | revision은 없지만 규칙 전부를 한 패킷에 싣는다 (`net/HostSession.kt:243-250`) |
| `totalEnemyCount = playerCount × 20` | PASS | `gameplay/MatchState.kt:75`, `BalanceConfigTest` |
| `maxActiveEnemies` 범위 검증 | PASS | `game/RoomSettings.kt` 8~12, `RoomSettingsTest` `동시 COM 슬라이더는 AUTO 와 8에서 12 사이만 고른다` |
| 볼륨을 LobbyState에 넣지 않음 | PASS | `game/RoomSettings.kt` `DeviceSettings` 분리, `game/SettingsStore.kt` 저장 |
| 음소거가 전파되지 않음 | PASS | 위와 같다 |

## §5 게임 시작 배리어

| 항목 | 판정 | 근거 |
|---|---|---|
| `START_PREPARE` 전송 | NOT_IMPLEMENTED | 준비 단계가 없다. START 한 통으로 끝난다 |
| `startServerTime` 2~4초 미래 | PARTIAL | 절대 시각 대신 3초 카운트다운을 Host가 센다 (`net/Protocol.kt:60`, `net/LobbyState.kt:154-158`) |
| 모든 Client가 맵 생성 | PASS | `game/NetDriver.kt:425` `beginStage` |
| 맵 해시 계산 | PASS | `map/StageData.gridHash`, `StageGeneratorTest` 결정성 검증 |
| 에셋·Config 버전 확인 | NOT_IMPLEMENTED | 교환하는 값이 없다 |
| `START_READY_ACK` | NOT_IMPLEMENTED | — |
| ACK에 해시 포함 | NOT_IMPLEMENTED | — |
| Host가 ACK 비교 | NOT_IMPLEMENTED | — |
| 해시 불일치 시 시작 중단 | **FAIL (P1)** | `05-COMMIT-02`. 비교 대상이 어긋나 있고 로그만 남긴다 |
| ACK 없는 Client 제외 | NOT_IMPLEMENTED | — |
| `START_COMMIT` 신뢰 전송 | PARTIAL | 한 번만 보낸다 (`net/HostSession.kt:105`). 유실되면 그 Client만 로비에 남는다 |
| 같은 기준 시각에서 3-2-1-GO | PARTIAL | 카운트다운 숫자는 LOBBY 패킷으로 함께 내려간다 (`LobbyFlowTest` `카운트다운은 참가자 화면에도 내려간다`). 첫 tick 시각은 START 도착 시점이라 RTT만큼 어긋난다 |
| 렌더 지연과 무관한 첫 tick | PARTIAL | 고정 timestep은 지킨다 (`core/FixedStepClockTest`). 기준 시각 공유는 위와 같다 |
| 카운트다운 중 입력 미적용 | PASS | 카운트다운 동안 화면이 로비다. 전투 입력 경로가 열리지 않는다 (`game/GameHost.kt:586`) |
| START 중복 수신 방어 | **FAIL (P2)** | `05-COMMIT-08` |

## §6 시간과 Tick 동기화

| 항목 | 판정 | 근거 |
|---|---|---|
| 주기적 Ping/Pong으로 오프셋 계산 | PARTIAL | 왕복 시간만 잰다. 시각 오프셋은 계산하지 않는다 (`net/ClientSession.kt:211-222`) |
| 여러 샘플의 중앙값/최솟값 | PARTIAL | 지수 평활 0.3 (`net/ClientSession.kt:219-221`). 중앙값은 아니다 |
| 60Hz 고정 timestep | PASS | `core/Constants.kt:12-16`, `FixedStepClockTest` 8건 |
| 스냅샷 20~30Hz | PASS | `net/Protocol.kt:43-45` |
| 렌더 FPS가 tick에 영향 없음 | PASS | `FixedStepClockTest` |
| `serverTick` 기준 보간 | PARTIAL | 수신 시각 기준으로 보간한다 (`net/ClientSession.kt:159-165`). 스냅샷 tick은 쓰지 않는다 |
| 큰 오차를 서서히 보정 | NOT_IMPLEMENTED | 스냅샷 값을 그대로 넣는다 (`net/SnapshotBridge.kt:76-79`) |
| 백그라운드 복귀 후 재동기 | NOT_IMPLEMENTED | `game/GameHost.kt:151` `onResume` 은 루프와 음소거만 되돌린다 |

## §7 입력 동기화

| 항목 | 판정 | 근거 |
|---|---|---|
| 4방향으로 접어 전송 | PASS | `input/TouchControls.kt` 가 접고 `game/GameHost.kt:620` 이 담는다. `TouchControlsTest` 16건 |
| 방향 유지와 버튼 edge 구분 | PASS | `input/TouchControls.kt:192-198` `consume()` 이 발사·특수기를 edge로 비운다 |
| 중복 발사 방지 | PASS | Host 쿨타임이 막는다 (`gameplay/GameWorld.fire`), `GameWorldTest` |
| `inputSequence` 역순·중복 처리 | **FAIL (P2)** | 검사 코드가 없다 (`net/HostSession.kt:175-178`) |
| 플레이어별 마지막 sequence 기록 | NOT_IMPLEMENTED | — |
| 마지막 이동 방향 유지 시간 제한 | **FAIL (P1)** | `07-INPUT-07` |
| 무입력 시 `NONE` 전환 | **FAIL (P1)** | 위와 같다 |
| 연사·특수기 속도를 Host가 차단 | PASS | 쿨타임을 Host에서 검사한다 (`gameplay/GameWorld`), `SpecialMoveTest` 20건 |
| 사망·탈락·카운트다운 입력 무시 | PASS | 살아 있는 탱크가 없으면 그대로 돌아선다 (`game/BattleScene.kt:200`) |
| 조이스틱 UI 즉시 반응 | PASS | 입력과 렌더가 같은 프레임에서 돈다 (`render/ControlsRenderer`) |
| Client prediction 사용 여부 명시 | PASS | 쓰지 않는다. Client는 스냅샷만 그린다 (`game/BattleScene.kt:229-234`) |
| prediction 오차 보정 | N/A | prediction을 쓰지 않는다 |
| 예측 효과 취소 | N/A | 위와 같다 |

## §8 Host 게임 판정

| 항목 | 판정 | 근거 |
|---|---|---|
| COM 생성을 Host만 결정 | PASS | `ai/AiDirector` 는 Host 경로에서만 돈다 (`game/BattleScene.kt:236`) |
| COM AI 전이를 Host만 실행 | PASS | 위와 같다. `AiDirectorTest` 13건, `TankAiTest` 21건 |
| 충돌을 Host만 판정 | PASS | Client는 `updateEffectsOnly` 만 실행한다 (`gameplay/GameWorld.kt:201-204`) |
| 피해·HP·Life를 Host만 적용 | PASS | `gameplay/MatchState`, `MatchStateTest` 20건 |
| entity 고유 `entityId` | PARTIAL | 판 안에서는 고유하다 (`gameplay/GameWorld.kt:88`·`129`). 판이 바뀌면 0부터 다시 시작한다 |
| 파괴된 ID 즉시 재사용 안 함 | PARTIAL | 증가만 하므로 판 안에서는 안전하다. 판 사이에는 겹친다 |
| Respawn을 Host가 결정 | PASS | `gameplay/MatchState.kt:158-163`, `game/BattleScene.kt:318` |
| 점수·Kill 귀속을 Host가 결정 | PASS | `gameplay/MatchState.kt:133-137` |
| 본진 상태를 Host가 결정 | PASS (Host) / FAIL (전파) | 판정은 Host가 한다 (`map/TileMap.kt:165-174`). 전파는 `10-BASE-06` |
| Friendly Fire를 Host가 적용 | PASS | `gameplay/GameWorld.Config.friendlyFire`, `RoomRuleTest` 아군 오사 3건 |
| Client 결과값을 믿지 않음 | PASS | Client→Host 메시지는 JOIN·READY·INPUT·PING·PONG·PRESENCE·LEAVE뿐이다 (`net/Protocol.kt:79-118`) |

## §9 GameSnapshot 동기화

| 항목 | 판정 | 근거 |
|---|---|---|
| 현재 serverTick 포함 | PASS | `net/Messages.kt:278` |
| 오래된 sequence 폐기 | **FAIL (P2)** | `09-SNAP-02` |
| 위치·방향·HP 포함 | PASS | `net/Messages.kt:255-265` |
| Life·생존 여부 포함 | PASS | `ScoreState` (`net/Messages.kt:275`) |
| 특수기 쿨타임·활성 포함 | PARTIAL | 활성 여부만 보낸다 (`net/Messages.kt:264`). 쿨타임 잔량은 없다 |
| COM 타입·위치·방향·HP 포함 | PASS | 탱크 목록에 진영 구분이 들어 있다 (`net/Messages.kt:296`) |
| 포탄 ID·위치·방향 포함 | PARTIAL | 소유자를 보내지 않는다 (`net/Messages.kt:267-273`) |
| 본진 상태·보호 포함 | PARTIAL | 파괴 여부만 있고 보호막은 없다. 적용도 하지 않는다 (`10-BASE-06`) |
| 남은 COM과 활성 COM 구분 | PARTIAL | 남은 수만 보낸다 (`net/Messages.kt:280`). 활성 수는 탱크 목록에서 세야 한다 |
| 변경 타일만 전송 | **FAIL (P1)** | `10-MAP-04`. 전체도 변경분도 보내지 않는다 |
| 신규 참가자용 keyframe | N/A | 게임 중 참가를 지원하지 않는다 (`net/HostSession.kt:215-218`) |
| 스냅샷 2~3개 버퍼링 | PASS | 두 장을 들고 그 사이를 메운다 (`net/ClientSession.kt:59-66`) |
| 과거 server time 기준 보간 | PASS | 한 간격 뒤에서 따라간다 (`net/ClientSession.kt:159-165`) |
| 순간이동·사망은 보간 제외 | PARTIAL | 새 entity는 보간하지 않는다 (`net/SnapshotBridge.kt:118-119`). Respawn 순간이동은 구분하지 않는다 |
| 작은 오차를 여러 프레임에 걸쳐 보정 | NOT_IMPLEMENTED | 스냅샷 값을 그대로 넣는다 |
| 큰 오차는 즉시 스냅 | PASS | 언제나 즉시 반영한다 |
| 없는 entity를 유예 후 제거 | PARTIAL | 유예 없이 즉시 제거한다 (`net/SnapshotBridge.kt:85-87`). 유실 한 번에 탱크가 깜빡인다 |
| 파괴된 entity 재등장 방지 | **FAIL (P2)** | sequence를 보지 않으므로 늦은 스냅샷이 되살린다 |

## §10 맵과 환경 상태

| 항목 | 판정 | 근거 |
|---|---|---|
| seed + version + settings로 결정 | PASS | `game/BattleScene.kt:133`, `StageGeneratorTest` `같은 seed 는 같은 맵을 만든다` |
| Host·Client 맵 해시 동일 | NOT_VERIFIED | 검증 코드가 어긋나 있어 확인 수단이 없다 (`05-COMMIT-02`) |
| 좌표 기반 hash로 variation 결정 | PASS | `map/StageGenerator`, `MapGuideTest` 14건 |
| BRICK 피해·파괴 전송 | **FAIL (P1)** | `10-MAP-04` |
| STEEL·관통탄 판정을 Host가 | PASS | `map/TileMap.kt:155-163`, `RoomRuleTest` |
| BASE 전환에 revision/tick | **FAIL (P1)** | `10-BASE-06` |
| WATER 프레임을 계산으로 | PASS | 시간으로 프레임을 고른다 (`render/WorldRenderer`) |
| 정적 타일을 초기 해시에 포함 | PASS | `gridHash` 가 전체 셀을 훑는다 (`map/StageData`) |
| 늦은 타일 이벤트가 되돌리지 않음 | N/A | 타일 이벤트가 없다 |

## §11 점수·사망·Respawn

| 항목 | 판정 | 근거 |
|---|---|---|
| 적 사망에 killer·enemy 식별 | PASS | `gameplay/MatchState.kt:133` `onEnemyDestroyed(killerSlot)` |
| 동일 enemy에 점수 중복 없음 | PASS | 파괴 경로가 하나다 (`gameplay/GameWorld` → `MatchBridge.onTankDestroyed`), `MatchStateTest` |
| HP 0이면 Life 정확히 1 감소 | PASS | `gameplay/MatchState.kt:145-165`, `MatchStateTest` |
| Respawn tick·위치를 Host가 전송 | PARTIAL | Host가 정하고 결과 위치만 스냅샷에 실린다. tick은 보내지 않는다 |
| 무적 시간이 모든 기기에서 동일 | NOT_VERIFIED | `spawnGuardRemaining` 을 스냅샷에 넣지 않는다 (`net/Messages.kt:255-265`) |
| Life 0이면 탈락 확정 | PASS | `gameplay/MatchState.kt:158-161` |
| 탈락자 입력 무시 | PASS | 살아 있는 탱크가 없으면 돌아선다 (`game/BattleScene.kt:200`) |
| HUD가 Host 상태만 표시 | PASS | `gameplay/MatchState.applyRemote` (`gameplay/MatchState.kt:204`) |
| 동시 처치 순서 고정 | NOT_VERIFIED | Host 단일 스레드라 결정적이지만 다중 기기 검증이 없다 |

## §12 지연·유실·중복·역순

| 항목 | 판정 | 근거 |
|---|---|---|
| RTT 0~400ms 테스트 | NOT_VERIFIED | 주입 수단이 없다 |
| 유실 1~20% 테스트 | NOT_VERIFIED | `LoopbackNetwork.dropNext` 로 단위 테스트에서만 흉내 낼 수 있다 |
| jitter 테스트 | NOT_VERIFIED | — |
| 역순 도착 강제 | NOT_VERIFIED | — |
| 중복 전달 | NOT_VERIFIED | — |
| 오래된 스냅샷이 덮지 않음 | **FAIL (P2)** | `09-SNAP-02` |
| 유실 시 무한 이동 방지 | **FAIL (P1)** | `07-INPUT-07` |
| 중복 발사가 두 발이 되지 않음 | PASS | 쿨타임이 막는다 |
| 중복 종료가 결과를 두 번 열지 않음 | PASS | `game/BattleScene.kt` `finishReported` 가 한 번만 통과시킨다 |
| 품질 표시를 판정에 쓰지 않음 | PASS | HUD 표시 전용 (`render/HudRenderer.kt`) |

## §13 Heartbeat와 연결 끊김

| 항목 | 판정 | 근거 |
|---|---|---|
| Host가 timeout으로 감지 | PASS | 4초 (`net/LobbyState.kt:137-148`), `SessionTest` 타임아웃 항목 |
| 지연과 끊김 구분 | PARTIAL | 단일 임계값 하나뿐이다 |
| `CONNECTED → SUSPECTED → DISCONNECTED` | NOT_IMPLEMENTED | 중간 상태가 없다 |
| SUSPECTED를 즉시 탈락 표시하지 않음 | N/A | 중간 상태가 없다 |
| timeout 후 확정 통보 | PARTIAL | LOBBY의 `connected=false` 로만 알린다 |
| 정책대로 DEAD/DISCONNECTED 처리 | **FAIL (P1)** | `13-CLIENT-08` |
| 남은 플레이어가 계속 진행 | PASS | Host 루프는 멈추지 않는다 (`game/NetDriver.kt:430-437`) |
| 잔여 탱크·포탄 정리 | **FAIL (P1)** | `13-CLIENT-08` |
| 총 COM 목표 유지 정책 고정 | PASS | 판을 열 때 정한 `totalEnemies` 를 그대로 쓴다 (`gameplay/MatchState.kt:75`) |
| 모든 Client가 Host timeout 감지 | PASS | `net/ClientSession.kt:146` |
| Host Migration 시도 안 함 | PASS | 구현이 없다 |
| 입력·simulation 즉시 중지 | **FAIL (P0)** | `13-HOST-03` |
| `HOST DISCONNECTED` 표시 | **FAIL (P0)** | 위와 같다 |
| 임의 승패 처리 안 함 | PASS | 결과를 만들지 않는다 |
| 네트워크 객체 정리 | PARTIAL | `closeRoom()` 은 있으나 끊김 경로에서 부르지 않는다 (`game/GameHost.kt:509`) |
| 복귀 정책이 모든 Client에서 동일 | **FAIL (P0)** | 복귀 자체가 없다 |
| 게임 중 재접속 거절 | PASS | `net/HostSession.kt:215-218` `ALREADY_STARTED` |
| 같은 playerId 신규 취급 안 함 | N/A | `playerId` 가 없다. 주소로 같은 기기를 알아본다 |
| 재접속 불가 안내 | PASS | 방 목록에서 사유를 띄운다 (`game/GameHost.kt:391`) |

## §14 앱 생명주기

| 항목 | 판정 | 근거 |
|---|---|---|
| 백그라운드 전환을 Host에 통지 | NOT_IMPLEMENTED | `game/GameHost.kt:145` `onPause` 는 루프만 멈춘다 |
| 백그라운드 timeout 정책 통일 | PARTIAL | 일반 타임아웃과 같아지지만 의도한 설계가 아니다 |
| 회전·폴더블에서 세션 유지 | PASS | 서피스 변경만 처리한다 (`GameSurfaceView`, `MainActivity`) |
| Wi-Fi 복구 시 중복 소켓 없음 | NOT_VERIFIED | 소켓을 다시 열지 않는다. 실환경 확인이 없다 |
| 모바일 데이터 전환 처리 | NOT_VERIFIED | 별도 처리가 없다 |
| 잠금 해제 후 재동기 | NOT_IMPLEMENTED | `onResume` 이 시간 기준을 다시 잡지 않는다 |
| 저사양 FPS 저하가 heartbeat를 막지 않음 | **FAIL (P2)** | `14-LIFE-08` |
| 송수신 스레드 독립 | **FAIL (P2)** | 위와 같다 |

## §15 게임 종료 판정

| 항목 | 판정 | 근거 |
|---|---|---|
| `remainingEnemies == 0` 을 Host만 판정 | PASS | `gameplay/MatchState.kt:229-233` |
| 마지막 점수 반영 후 결과 계산 | PASS | `onEnemyDestroyed` 안에서 점수를 올린 뒤 `evaluate()` 를 부른다 (`gameplay/MatchState.kt:133-137`) |
| Kill Count 비교 | PASS | `gameplay/MatchState.kt:240-256`, `MatchStateTest` |
| 동점 시 Life → HP → 공동 승리 | PASS | `balance.tieBreakOrder`, `MatchStateTest` |
| 본진 파괴를 Host만 확정 | PASS | `map/TileMap.kt:165-174` → `MatchState.onBaseDestroyed` |
| 아군 탄환도 동일 처리 | PASS | 진영을 보지 않는다 (`map/TileMap.kt:165`), `RoomRuleTest` |
| 전원 Life 소진을 Host만 확정 | PASS | `gameplay/MatchState.kt:222-227` |
| Victory·Game Over 동시 발생 우선순위 | PASS | `evaluate()` 가 전원 탈락을 먼저 본다 (`gameplay/MatchState.kt:222`). 본진 파괴는 별도 경로로 즉시 확정한다 (`gameplay/MatchState.kt:176`) |
| `PLAYING → ENDING` 한 번만 | PASS | `phase != PLAYING` 이면 즉시 돌아선다 (`gameplay/MatchState.kt:177`·`220`) |
| 종료 후 입력·Spawn·점수 차단 | PARTIAL | `update()` 는 멈추지만 (`gameplay/MatchState.kt:188`) 결과 화면 전환까지 2.5초 동안 세계는 계속 돈다 (`game/BattleScene.kt`) |
| 결과에 endReason·winner·score 포함 | PARTIAL | Host 내부에는 있다 (`gameplay/MatchState.kt:92-100`). 네트워크로는 `phase` 와 점수만 간다 |
| `endServerTick` 포함 | NOT_IMPLEMENTED | — |

## §16 GAME_ENDED와 결과 화면

| 항목 | 판정 | 근거 |
|---|---|---|
| 신뢰 전송 | PARTIAL | 전용 메시지가 없다. 20Hz 스냅샷이 `phase` 를 반복한다 |
| 중복 수신 멱등 | PASS | `finishReported` (`game/BattleScene.kt`) |
| `RESULT_ACK` | NOT_IMPLEMENTED | — |
| 미수신 Client에 재전송 | PARTIAL | 스냅샷 반복이 사실상 재전송 구실을 한다 |
| 결과 화면에서 렌더·입력 중지 | PASS | `game/GameHost.kt:689` 이 RESULT에서 결과 화면만 그린다 |
| 모든 기기에서 순위·Kill·Life·HP 동일 | NOT_VERIFIED | 스냅샷 점수로 맞추지만 다중 기기 확인이 없다 |
| `resultHash` 불일치 시 로그 | NOT_IMPLEMENTED | 결과 해시가 없다 |
| Host 끊김 결과에 승자 확정 안 함 | PASS | 결과를 만들지 않는다 (다만 `13-HOST-03` 으로 화면이 멈춘다) |

## §17 재경기와 로비 복귀

| 항목 | 판정 | 근거 |
|---|---|---|
| Host만 로비 복귀 확정 | PASS | `net/HostSession.kt:120-124` `reopenLobby` |
| 새 revision 발급 | NOT_IMPLEMENTED | revision이 없다 |
| 모든 Ready를 false로 | PASS | `net/LobbyState.kt:180-190` `reopen`, `Phase9SessionTest` `판이 끝나고 로비를 다시 열면 사람은 남고 준비만 풀린다` |
| 탱크 선택 정책 통일 | PASS | 유지한다. `reopen` 이 이름·탱크·색을 건드리지 않는다 |
| 이전 match 상태 제거 | PASS | 판마다 `GameWorld`·`MatchState` 를 새로 만든다 (`game/BattleScene.kt:131-135`) |
| 이전 matchId 패킷 폐기 | NOT_IMPLEMENTED | `matchId` 가 없다. 재경기 직후 이전 스냅샷이 새 entity에 적용될 수 있다 |
| 로비 BGM 전환 | PASS | `game/GameHost.kt:406` |
| 재경기 요청과 확정 구분 | PASS | 방장만 확정한다 (`net/HostSession.kt:134-149`) |
| 새 matchId 발급 | NOT_IMPLEMENTED | `stageIndex` 만 올린다 |
| 새 seed 사용 여부를 설정대로 | PASS | `randomSeed == 0` 이면 새로 뽑는다 (`game/NetDriver.kt:171-176`) |
| 동일 맵 재경기 시 해시 확인 | NOT_VERIFIED | `05-COMMIT-02` 로 해시 검증 자체가 동작하지 않는다 |
| START_PREPARE 배리어 재수행 | PARTIAL | 카운트다운은 다시 돈다. 준비 배리어는 원래 없다 |
| 끊긴 플레이어 자동 포함 안 함 | PASS | `net/HostSession.kt:140-144`, `Phase9SessionTest` `결과 화면을 떠난 사람은 다음 판에서 빠진다` |
| 2명 미만이면 재경기 안 함 | PASS | `Phase9SessionTest` `아무도 안 남으면 다시 시작할 수 없다` |

## §18 유효성·오류 입력 방어

| 항목 | 판정 | 근거 |
|---|---|---|
| 알 수 없는 playerId 입력 무시 | PASS | `slotOf(from)` 이 없으면 버린다 (`net/HostSession.kt:175`) |
| 타인 가장 입력 차단 | PASS | 슬롯을 패킷 출처로만 정한다. 메시지에 슬롯 필드가 없다 |
| 입력 rate limit | NOT_IMPLEMENTED | 검사하지 않는다 |
| 잘못된 direction 거절 | PASS | `coerceIn(0, 3)` (`game/BattleScene.kt:202`) |
| 쿨타임 Host 검증 | PASS | `SpecialMoveTest` 20건 |
| 맵 밖 위치 거절 | PASS | 위치를 받지 않는다. Host가 계산한다 |
| Client가 점수·HP·승패를 못 보냄 | PASS | 그런 메시지 타입이 없다 (`net/Protocol.kt:79-118`) |
| 패킷 크기·entity 수 제한 | PARTIAL | 패킷 상한 1200바이트 (`net/Protocol.kt:38`). 탱크·포탄 개수를 넘치면 잘린다 |
| 잘못된 메시지가 스레드를 죽이지 않음 | PARTIAL | `readType` 이 걸러 내지만 본문 파싱에는 예외 방어가 없다 (`net/Packet.kt`) |
| 오류 사유 진단 로그 | PARTIAL | `Log.w` 몇 곳뿐이다 |

## §19 로그와 Desync 진단

| 항목 | 판정 | 근거 |
|---|---|---|
| 세션 시작·종료에 sessionId·matchId 기록 | NOT_IMPLEMENTED | 식별자가 없다 |
| 입장·퇴장·Ready·Start 전이 기록 | PARTIAL | 입장·퇴장·시작만 남긴다 (`game/NetDriver.kt:306`·`313`·`320` (입장·퇴장·시작 로그)) |
| 최근 sequence 순환 버퍼 | NOT_IMPLEMENTED | — |
| 1~2초 간격 checksum 비교 | NOT_IMPLEMENTED | — |
| checksum 구성 요소 | NOT_IMPLEMENTED | — |
| 효과·오디오·UI를 checksum에서 제외 | N/A | checksum이 없다 |
| Desync 발견 시 복구 | PARTIAL | 스냅샷이 매번 상태를 덮으므로 위치·HP는 저절로 맞는다. 타일은 맞지 않는다 |
| 반복 Desync 시 파일 저장 | NOT_IMPLEMENTED | — |
| 출시 빌드 주소 마스킹 | NOT_IMPLEMENTED | `Log.i` 에 주소를 그대로 쓰지는 않지만 마스킹 규칙도 없다 |

## §22 출시 승인 기준

| 항목 | 판정 |
|---|---|
| 2~4인 로비 100회 반복 일치 | NOT_VERIFIED |
| 서로 다른 맵으로 시작하지 않음 | NOT_VERIFIED (`05-COMMIT-02` 로 검증 수단 없음) |
| 10% 유실 30분 후 상태 일치 | NOT_VERIFIED |
| 중복 Kill·점수 없음 | PASS (`MatchStateTest`) |
| Client가 피해·점수·승패를 못 정함 | PASS (`net/Protocol.kt:79-118`) |
| Client 이탈 후 완주 가능 | PARTIAL (`13-CLIENT-08`) |
| Host 이탈 시 동일 종료 | **FAIL** (`13-HOST-03`) |
| 모든 종료 조건에서 결과 일치 | NOT_VERIFIED |
| 재경기 후 이전 상태 제거 | PARTIAL (matchId 없음) |
| 진단 로그 존재 | **FAIL** (session/match/tick/hash 로그 없음) |

---

# 부록 B. 실기기 2대 수동 테스트 절차

연결이 성립해야 이번 감사의 `NOT_VERIFIED` 항목을 판정할 수 있다. 다음 순서로 진행한다.

1. 두 기기를 같은 공유기의 같은 대역(2.4GHz 또는 5GHz)에 붙인다. AP isolation을 끈다.
2. `release/battle-city-local-assault-release.apk` 를 두 대에 설치한다.
3. `adb logcat -s BattleCity` 를 두 대에서 각각 파일로 받는다.
4. A기기에서 CREATE GAME, B기기에서 JOIN GAME으로 목록을 확인한 뒤 방을 고른다.
5. B기기에서 이름·탱크·색을 바꾸고 A기기 화면에 반영되는지 본다.
6. B기기에서 READY를 누르고 A기기 단추가 `START GAME` 으로 바뀌는지 본다.
7. 시작 직후 B기기 logcat에서 `맵이 어긋났다` 가 나오는지 확인한다. (`05-COMMIT-02` 재현)
8. A기기에서 벽돌을 부수고 B기기 화면에 반영되는지 본다. (`10-MAP-04` 재현)
9. A기기에서 본진을 부수고 B기기 화면을 본다. (`10-BASE-06` 재현)
10. 게임 중 B기기 Wi-Fi를 끄고 A기기에 남는 탱크를 본다. (`13-CLIENT-08` 재현)
11. 게임 중 A기기 앱을 강제 종료하고 B기기가 어떻게 되는지 본다. (`13-HOST-03` 재현)

각 단계에서 두 기기의 화면을 함께 촬영하고 logcat 시각을 맞춰 기록한다.

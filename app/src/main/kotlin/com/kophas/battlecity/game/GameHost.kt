package com.kophas.battlecity.game

import android.app.ActivityManager
import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import android.view.Surface
import com.kophas.battlecity.audio.AndroidPlayback
import com.kophas.battlecity.audio.AudioDirector
import com.kophas.battlecity.audio.AudioSettings
import com.kophas.battlecity.core.GameLoop
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.input.TouchControls
import com.kophas.battlecity.map.Rng
import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.net.Protocol
import com.kophas.battlecity.net.RoomScanner
import com.kophas.battlecity.net.UdpTransport
import com.kophas.battlecity.render.AssetSource
import com.kophas.battlecity.render.ControlsRenderer
import com.kophas.battlecity.render.GameAssets
import com.kophas.battlecity.render.NativeRenderer
import com.kophas.battlecity.render.RendererBackend
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.StageBox
import com.kophas.battlecity.render.Viewport
import java.util.concurrent.atomic.AtomicReference

/**
 * 서피스 수명과 게임 루프를 잇는 지점이자, 화면 사이를 오가는 곳. (계획서 §27)
 *
 * Vulkan / EGL 컨텍스트는 생성한 스레드에 묶이므로, 서피스 이벤트를 UI 스레드에서
 * 즉시 처리하지 않고 명령으로 쌓아 두었다가 **게임 루프 스레드에서** 소비한다.
 *
 * ```
 *  MENU ──CREATE/JOIN──▶ LOBBY ──START──▶ BATTLE ──승패──▶ RESULT
 *   ▲                      ▲                                 │
 *   └──────MAIN MENU───────┴──────────LOBBY───────────────────┘
 * ```
 *
 * 화면마다 씬이 따로 있고, 손가락은 지금 화면에만 간다. 화면 하나가 다른 화면의
 * 사정을 알 필요가 없도록 갈림길은 전부 여기 모아 둔다.
 */
class GameHost(
    private val assetManager: AssetManager,
    /** 소리와 진동, 설정 저장에 필요하다. 없으면 조용히 돌아간다. */
    private val context: Context? = null,
) : GameLoop.Callbacks {

    /** 지금 보고 있는 화면. */
    enum class Screen { MENU, ROOMS, LOBBY, BATTLE, RESULT }

    /** 화면 위에 덮이는 판. 게임 설정과 사운드 설정이 서로 다른 화면에서 열린다. */
    enum class Overlay { NONE, GAME_SETTINGS, SOUND }

    private sealed interface SurfaceCommand {
        data class Attach(val surface: Surface, val width: Int, val height: Int) : SurfaceCommand
        data class Resize(val width: Int, val height: Int) : SurfaceCommand
        data object Detach : SurfaceCommand
    }

    private val pendingCommand = AtomicReference<SurfaceCommand?>(null)

    private val renderer = NativeRenderer()
    private val batch = SpriteBatch()

    /** 가상 조이스틱과 버튼. (계획서 §18) */
    val touch = TouchControls()

    private val viewport = Viewport(DEFAULT_LOGICAL, DEFAULT_LOGICAL)
    private val loop = GameLoop(this)

    private var controlsRenderer: ControlsRenderer? = null
    private var lobbyScene: LobbyScene? = null
    private var menuScene: MainMenuScene? = null
    private var roomListScene: RoomListScene? = null
    private var gameSettingsScene: GameSettingsScene? = null
    private var soundSettingsScene: SoundSettingsScene? = null
    private var resultScene: ResultScene? = null
    private var audio: AudioDirector? = null
    private var playback: AndroidPlayback? = null
    private var scanner: RoomScanner? = null
    private var store: SettingsStore? = null
    private var tick = 0L

    private var assets: GameAssets? = null
    private var balance: BalanceConfig? = null
    private var catalog: SpriteCatalog? = null
    private var scene: BattleScene? = null
    private var netDriver: NetDriver? = null
    private var insets = Viewport.Insets.NONE

    private var screen: Screen = Screen.MENU

    /**
     * 화면 위에 덮인 설정판. 두 장이 함께 열리는 일은 없다.
     *
     * 게임 설정은 로비에서 방장만, 사운드 설정은 메인 메뉴에서 연다. 열려 있는
     * 동안에는 손가락과 그림이 모두 이 판으로만 간다.
     */
    private var overlay: Overlay = Overlay.NONE
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * 이 방의 규칙. 로비에서 방장이 게임 설정 화면으로 고친다.
     *
     * 방이 닫혀도 남겨 둔다. 다음에 여는 방이 같은 규칙으로 시작하므로 판마다
     * 다시 맞출 일이 없다.
     */
    private var roomSettings = RoomSettings()

    /** 이 기기의 소리 크기. 방과 무관하게 여기에만 걸린다. */
    private var deviceSettings = DeviceSettings()

    /** 마지막으로 소리를 낸 카운트다운 초. 같은 초에 두 번 울리지 않게 한다. */
    private var lastCountdownSecond = -1

    var preferVulkan: Boolean = true

    /**
     * 시작하자마자 들어갈 역할. (계획서 §4.2)
     *
     * LOCAL 이면 메뉴부터 시작한다. HOST/CLIENT 를 실행 인자로 주면 메뉴를 건너뛰고
     * 곧장 방으로 간다. 두 기기를 붙여 시험할 때 손이 덜 간다.
     */
    var netRole: NetRole = NetRole.LOCAL

    /** 메뉴 없이 혼자 판을 연다. 화면을 눈으로 확인할 때 쓴다. */
    var soloDebug: Boolean = false

    /** Client 로 붙을 때 직접 지정한 Host 주소. null 이면 브로드캐스트로 찾는다. */
    var hostAddress: String? = null

    var playerName: String = ""

    val backend: RendererBackend get() = renderer.backend

    // -----------------------------------------------------------------------
    // 서피스
    // -----------------------------------------------------------------------

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        pendingCommand.set(SurfaceCommand.Attach(surface, width, height))
        loop.start()
        loop.resume()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        pendingCommand.set(SurfaceCommand.Resize(width, height))
        resizeScenes(width, height)
    }

    fun onSurfaceDestroyed() {
        pendingCommand.set(SurfaceCommand.Detach)
        // 렌더 스레드가 Detach 를 소비할 기회를 준 뒤 정지시킨다.
        loop.stop()
        drainCommands()
    }

    fun onPause() {
        loop.pause()
        // 화면이 꺼졌는데 소리가 계속 나면 안 된다.
        audio?.setMuted(true)
    }

    fun onResume() {
        loop.resume()
        audio?.setMuted(false)
    }

    fun setInsets(insets: Viewport.Insets) {
        this.insets = insets
    }

    fun release() {
        loop.stop()
        closeRoom()
        scanner?.close()
        scanner = null
        playback?.release()
        playback = null
        audio = null
        renderer.detachSurface()
        renderer.close()
    }

    private fun resizeScenes(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        touch.resize(width, height)
        lobbyScene?.resize(width, height)
        menuScene?.resize(width, height)
        roomListScene?.resize(width, height)
        gameSettingsScene?.resize(width, height)
        soundSettingsScene?.resize(width, height)
        resultScene?.resize(width, height)
    }

    // -----------------------------------------------------------------------
    // 손가락 (계획서 §18)
    //
    // 화면마다 받는 것이 다르다. 로비와 메뉴는 눌린 자리만 보고, 전투는 조이스틱을
    // 끌고 다니며, 설정은 슬라이더를 끈다. 갈림길을 씬 안에 두면 씬이 게임 상태를
    // 알아야 하므로 여기서 가른다.
    // -----------------------------------------------------------------------

    fun onTouchDown(pointerId: Int, x: Float, y: Float) {
        when (overlay) {
            Overlay.GAME_SETTINGS -> {
                gameSettingsScene?.onDown(x, y)
                return
            }
            Overlay.SOUND -> {
                soundSettingsScene?.onDown(x, y)
                return
            }
            Overlay.NONE -> Unit
        }
        when (screen) {
            Screen.MENU -> menuScene?.onTap(x, y)
            Screen.ROOMS -> roomListScene?.onDown(x, y)
            Screen.LOBBY -> handleLobbyTap(x, y)
            Screen.BATTLE -> touch.onDown(pointerId, x, y)
            Screen.RESULT -> resultScene?.onDown(x, y)
        }
    }

    fun onTouchMove(pointerId: Int, x: Float, y: Float) {
        when (overlay) {
            Overlay.GAME_SETTINGS -> {
                gameSettingsScene?.onMove(x, y)
                return
            }
            Overlay.SOUND -> {
                soundSettingsScene?.onMove(x, y)
                return
            }
            Overlay.NONE -> Unit
        }
        if (screen == Screen.BATTLE) touch.onMove(pointerId, x, y)
    }

    fun onTouchUp(pointerId: Int, x: Float, y: Float) {
        when (overlay) {
            Overlay.GAME_SETTINGS -> {
                gameSettingsScene?.onUp(x, y)?.let { applyGameSettingsAction(it) }
                return
            }
            Overlay.SOUND -> {
                soundSettingsScene?.onUp(x, y)?.let { applySoundSettingsAction(it) }
                return
            }
            Overlay.NONE -> Unit
        }
        when (screen) {
            Screen.MENU -> {
                menuScene?.let { menu ->
                    // 누른 자리에서 손을 떼야 눌린 것으로 본다. 끌어서 벗어나면 취소다.
                    val action = menu.onTap(x, y)
                    menu.onRelease()
                    handleMenuAction(action)
                }
            }
            Screen.ROOMS -> roomListScene?.onUp(x, y)?.let { handleRoomListAction(it) }
            Screen.LOBBY -> lobbyScene?.onRelease()
            Screen.BATTLE -> touch.onUp(pointerId)
            Screen.RESULT -> resultScene?.onUp(x, y)?.let { handleResultAction(it) }
        }
    }

    fun onTouchCancel() {
        gameSettingsScene?.onCancelTouch()
        soundSettingsScene?.onCancelTouch()
        resultScene?.onCancelTouch()
        roomListScene?.onCancelTouch()
        menuScene?.onRelease()
        when (screen) {
            Screen.LOBBY -> lobbyScene?.onRelease()
            Screen.BATTLE -> touch.onCancel()
            else -> Unit
        }
    }

    private fun handleMenuAction(action: MainMenuScene.Action) {
        when (action) {
            MainMenuScene.Action.CreateGame -> {
                tap()
                openRoom(NetRole.HOST)
            }

            MainMenuScene.Action.JoinGame -> {
                tap()
                // 바로 붙지 않고 목록을 보여 준다. 방이 여럿일 수 있다. (계획서 §27)
                roomListScene?.lastDenial = null
                screen = Screen.ROOMS
            }

            MainMenuScene.Action.OpenSound -> {
                tap()
                openSoundSettings()
            }

            MainMenuScene.Action.None -> Unit
        }
    }

    private fun handleLobbyTap(x: Float, y: Float) {
        val lobby = lobbyScene ?: return
        val driver = netDriver ?: return
        tap()
        when (val action = lobby.onTap(x, y)) {
            LobbyScene.Action.ToggleReady -> {
                driver.toggleReady()
                audio?.play(AudioDirector.Event.UI_READY)
            }
            LobbyScene.Action.Start -> {
                // 카운트다운이 시작될 때만 소리를 낸다. 아직 덜 모였으면 아무 일도 없다.
                if (driver.startMatch(scene)) lastCountdownSecond = -1
            }
            LobbyScene.Action.CycleType -> driver.cycleTankType()
            LobbyScene.Action.CycleColor -> driver.cycleColor()
            LobbyScene.Action.OpenGameSettings -> openGameSettings()
            LobbyScene.Action.Back -> returnToMenu()
            is LobbyScene.Action.SetName -> {
                driver.setName(action.name)
                playerName = action.name
                store?.saveName(action.name)
            }
            LobbyScene.Action.None -> Unit
        }
    }

    private fun handleResultAction(action: ResultScene.Action) {
        val driver = netDriver
        tap()
        when (action) {
            ResultScene.Action.PlayAgain -> {
                if (driver == null || driver.role == NetRole.LOCAL) {
                    replaySolo()
                } else if (driver.playAgain(scene)) {
                    // 준비를 다시 받지 않는다. 남아 있는 것이 곧 다시 하겠다는 뜻이다.
                    screen = Screen.LOBBY
                    lastCountdownSecond = -1
                    audio?.setTrack(AudioDirector.Track.LOBBY)
                }
            }

            ResultScene.Action.ReturnToLobby -> {
                if (driver == null || driver.role == NetRole.LOCAL) {
                    // 혼자 하는 판에는 로비가 없다. 새 판을 여는 것이 곧 로비다.
                    replaySolo()
                } else {
                    driver.setResultPresence(false)
                    driver.returnToLobby()
                    screen = Screen.LOBBY
                    audio?.setTrack(AudioDirector.Track.LOBBY)
                }
            }

            ResultScene.Action.MainMenu -> {
                driver?.setResultPresence(false)
                returnToMenu()
            }

            ResultScene.Action.None -> Unit
        }
    }

    private fun tap() = audio?.vibrate(AudioDirector.Haptic.UI_TAP)

    /** 목록에서 고른 방. 답을 기다리는 동안 목록을 그대로 두고 위에 표시만 한다. */
    private var joiningRoom: com.kophas.battlecity.net.RoomScanner.Room? = null

    private fun handleRoomListAction(action: RoomListScene.Action) {
        tap()
        when (action) {
            RoomListScene.Action.Refresh -> {
                scanner?.refresh()
                roomListScene?.lastDenial = null
            }

            RoomListScene.Action.Back -> {
                // 답을 기다리던 중이면 그만둔다. 그대로 두면 뒤늦게 로비로 끌려간다.
                cancelJoin()
                screen = Screen.MENU
            }

            is RoomListScene.Action.Join -> {
                joiningRoom = action.room
                roomListScene?.joining = action.room
                roomListScene?.lastDenial = null
                hostAddress = action.room.peer.address
                openRoom(NetRole.CLIENT, keepScanner = true)
            }

            RoomListScene.Action.None -> Unit
        }
    }

    private fun cancelJoin() {
        if (joiningRoom == null) return
        joiningRoom = null
        roomListScene?.joining = null
        closeRoom()
    }

    /**
     * 입장을 거절당했다. 목록을 고친 뒤 제자리로 돌려보낸다. (계획서 §28)
     *
     * 고른 순간과 들어가는 순간 사이에 방이 바뀔 수 있다. 그 사이를 없앨 수는
     * 없으므로, 거절당한 까닭을 목록에 반영해 다음 선택이 헛되지 않게 한다.
     */
    private fun onJoinDenied(reason: Int) {
        val room = joiningRoom
        joiningRoom = null
        roomListScene?.joining = null
        closeRoom()

        when (reason) {
            Protocol.Deny.ROOM_FULL -> {
                // 자리가 날 수도 있으니 지우지 않고 잠근다.
                room?.let { scanner?.markFull(it.peer) }
                roomListScene?.lastDenial = "ROOM IS FULL"
            }

            Protocol.Deny.ALREADY_STARTED -> {
                // 이미 시작한 방은 들어갈 수 없다. 목록에 둘 이유가 없다.
                room?.let { scanner?.remove(it.peer) }
                roomListScene?.lastDenial = "GAME ALREADY STARTED"
            }

            else -> roomListScene?.lastDenial = "CANNOT JOIN"
        }
        screen = Screen.ROOMS
        audio?.play(AudioDirector.Event.NETWORK_LOST)
    }

    private fun enterLobby() {
        joiningRoom = null
        roomListScene?.joining = null
        scanner?.close()
        scanner = null
        screen = Screen.LOBBY
        audio?.setTrack(AudioDirector.Track.LOBBY)
    }

    /** 방을 닫고 메인 메뉴로. 로비와 결과 화면이 함께 쓴다. */
    private fun returnToMenu() {
        closeRoom()
        overlay = Overlay.NONE
        screen = Screen.MENU
        openScanner()
        audio?.stopAllLoops()
        audio?.setTrack(AudioDirector.Track.MENU)
    }

    // -----------------------------------------------------------------------
    // 화면 전환
    // -----------------------------------------------------------------------

    /** 게임 설정. 방 규칙은 방장 것이라 방장이 아니면 열지 않는다. (계획서 §44.2) */
    private fun openGameSettings() {
        val settings = gameSettingsScene ?: return
        val driver = netDriver
        if (driver != null && driver.role != NetRole.HOST) return
        settings.open(roomSettings)
        overlay = Overlay.GAME_SETTINGS
    }

    private fun openSoundSettings() {
        val settings = soundSettingsScene ?: return
        settings.open(deviceSettings)
        overlay = Overlay.SOUND
    }

    private fun applyGameSettingsAction(action: GameSettingsScene.Action) {
        when (action) {
            is GameSettingsScene.Action.Apply -> {
                roomSettings = action.room
                // 방장이 바꾼 규칙은 로비 현황에 바로 실린다. START 를 기다리지 않는다.
                netDriver?.roomSettings = roomSettings
                overlay = Overlay.NONE
                tap()
            }

            GameSettingsScene.Action.Cancel -> {
                overlay = Overlay.NONE
                tap()
            }

            GameSettingsScene.Action.None -> Unit
        }
    }

    private fun applySoundSettingsAction(action: SoundSettingsScene.Action) {
        when (action) {
            is SoundSettingsScene.Action.Apply -> {
                deviceSettings = action.device
                store?.saveDevice(action.device)
                applyVolumes()
                overlay = Overlay.NONE
                tap()
            }

            SoundSettingsScene.Action.Cancel -> {
                // 취소하면 씬이 열었을 때 값을 되돌려 준다. 그 값으로 다시 맞춘다.
                applyVolumes()
                overlay = Overlay.NONE
                tap()
            }

            SoundSettingsScene.Action.None -> Unit
        }
    }

    private fun applyVolumes() {
        audio?.setVolumes(deviceSettings.bgmScale, deviceSettings.sfxScale)
    }

    /** 방을 연다(HOST) 또는 방에 붙는다(CLIENT). */
    private fun openRoom(role: NetRole, keepScanner: Boolean = false) {
        val loaded = assets ?: return
        val rules = balance ?: return
        val art = catalog ?: return
        closeRoom()
        if (!keepScanner) {
            scanner?.close()
            scanner = null
        }

        val driver = NetDriver(role, playerName, hostAddress, art.palette.size)
        // 이 기기를 쥔 사람의 자리에도 AI 를 붙이지 않는다. 조종간이 둘이 되기 때문이다.
        driver.humanSlots += driver.localSlot
        driver.roomSettings = roomSettings
        netDriver = driver

        val newScene = BattleScene(
            assets = loaded,
            balance = rules,
            role = role,
            humanSlots = driver.humanSlots,
        )
        newScene.room = roomSettings
        newScene.audio = audio
        newScene.localSlot = driver.localSlot
        newScene.reducedEffects = reducedEffects
        newScene.showNetwork = role != NetRole.LOCAL
        newScene.onMatchFinished = { enterResult() }
        driver.onMatchStarted = { enterBattle() }
        driver.onJoined = { enterLobby() }
        driver.onDenied = { reason -> onJoinDenied(reason) }
        driver.onDisconnected = { onHostLost() }
        driver.onStartRejected = { onStartRejected() }
        driver.attachScene(newScene)
        scene = newScene
        viewport.resizeWorld(newScene.logicalWidth, newScene.logicalHeight)

        // 방에 붙는 중이면 자리를 받을 때까지 목록에 머문다. 들어가지도 못했는데
        // 로비를 보여 주면 남의 방에 들어간 것처럼 읽힌다.
        screen = when {
            role == NetRole.LOCAL -> Screen.BATTLE
            keepScanner -> Screen.ROOMS
            else -> Screen.LOBBY
        }
        lastCountdownSecond = -1
        audio?.setTrack(if (role == NetRole.LOCAL) AudioDirector.Track.BATTLE else AudioDirector.Track.LOBBY)
    }

    private fun closeRoom() {
        netDriver?.close()
        netDriver = null
        scene = null
    }

    /** 메뉴에 있는 동안만 같은 망을 살핀다. 방에 들어가면 소켓을 놓아준다. */
    private fun openScanner() {
        if (scanner != null) return
        scanner = runCatching {
            RoomScanner(UdpTransport(0), System::currentTimeMillis)
        }.onFailure { Log.w(TAG, "방을 찾아볼 소켓을 열지 못했다 ($it)") }.getOrNull()
    }

    private fun enterResult() {
        val current = scene ?: return
        val driver = netDriver
        val solo = driver == null || driver.role == NetRole.LOCAL
        resultScene?.showPresence = !solo && driver?.role == NetRole.HOST
        resultScene?.presenceOf = { slot -> driver?.isPresentInResult(slot) ?: true }
        resultScene?.show(
            match = current.matchState,
            stageIndex = current.stage,
            localSlot = current.localSlot,
            isHost = solo || driver?.role == NetRole.HOST,
            // 혼자 하는 판에는 기다릴 사람이 없다. 언제나 다시 할 수 있다.
            peersPresent = if (solo) 1 else driver?.resultPeerCount ?: 0,
        )
        driver?.setResultPresence(true)
        screen = Screen.RESULT
        audio?.stopAllLoops()
    }

    /**
     * Host 를 잃었다. (계획서 §37)
     *
     * 전투 화면에는 나가는 길이 없다. 그대로 두면 멈춘 화면에 갇히므로 결과 화면으로
     * 보낸다. 승자와 점수는 확정하지 않는다. 끝까지 간 판이 아니기 때문이다.
     * 로비에서 잃었으면 돌아갈 방이 없으니 곧바로 메뉴로 돌린다.
     */
    private fun onHostLost() {
        audio?.stopAllLoops()
        audio?.play(AudioDirector.Event.NETWORK_LOST)

        val current = scene
        if (current == null || screen == Screen.MENU || screen == Screen.ROOMS) {
            returnToMenu()
            return
        }
        if (screen == Screen.LOBBY) {
            returnToMenu()
            roomListScene?.lastDenial = "HOST DISCONNECTED"
            screen = Screen.ROOMS
            return
        }
        resultScene?.showPresence = false
        resultScene?.showHostLost(current.matchState, current.stage, current.localSlot)
        screen = Screen.RESULT
    }

    /**
     * 맵이 어긋나 판을 열지 못했다. (계획서 §35)
     *
     * 방을 나가고 목록으로 돌려보낸다. 같은 방에 다시 들어가 봐야 같은 결과다.
     */
    private fun onStartRejected() {
        returnToMenu()
        roomListScene?.lastDenial = "MAP MISMATCH"
        screen = Screen.ROOMS
    }

    private fun enterBattle() {
        screen = Screen.BATTLE
        lastCountdownSecond = -1
        audio?.setTrack(AudioDirector.Track.BATTLE)
    }

    /** 혼자 하는 판을 다시 연다. 씨앗만 바꾸면 새 맵이 나온다. */
    private fun replaySolo() {
        val current = scene ?: return
        current.beginStage(Rng.advanceSeed(System.currentTimeMillis()), current.stage + 1, DEFAULT_PLAYERS)
        screen = Screen.BATTLE
        audio?.setTrack(AudioDirector.Track.BATTLE)
    }

    // -----------------------------------------------------------------------
    // 루프
    // -----------------------------------------------------------------------

    override fun onUpdate(tickIndex: Long, tickSeconds: Float) {
        tick++
        if (overlay != Overlay.NONE) {
            // 설정판이 덮여 있어도 방은 살아 있어야 한다. 세션을 굴리지 않으면
            // 알림이 끊겨 참가자 화면에서 방장이 사라진다. (계획서 §37)
            //
            // 게임 설정은 로비에 있는 방장만 열 수 있어 이 사이에 판이 시작될 일은
            // 없다. START 를 누를 손이 판에 덮여 있다.
            if (screen == Screen.LOBBY) netDriver?.onTick()
            return
        }

        when (screen) {
            Screen.MENU -> {
                scanner?.update()
                menuScene?.let { menu ->
                    menu.playerName = playerName.ifBlank { DEFAULT_NAME }
                    menu.roomsFound = scanner?.roomCount
                }
                return
            }

            Screen.ROOMS -> {
                scanner?.update()
                // 붙는 중이면 세션도 굴려야 한다. 답(자리 배정 · 거절)이 여기로 온다.
                netDriver?.onTick()
                roomListScene?.let { list ->
                    list.rooms = scanner?.list.orEmpty()
                    list.scanning = list.rooms.isEmpty() && scanner != null
                }
                return
            }

            Screen.LOBBY -> {
                netDriver?.onTick()
                // 판이 열리면 세션이 알려 준다. 화면은 그 신호를 따라간다.
                updateCountdownAudio()
                return
            }

            Screen.RESULT -> {
                netDriver?.onTick()
                // 남아 있는 사람 수는 계속 바뀐다. 마지막 한 명이 나가면 버튼이 잠긴다.
                netDriver?.let { driver ->
                    if (driver.role == NetRole.HOST) {
                        resultScene?.peersPresent = driver.resultPeerCount
                    }
                }
                return
            }

            Screen.BATTLE -> Unit
        }

        val currentScene = scene ?: return
        val driver = netDriver
        driver?.onTick()

        if (driver != null) {
            currentScene.localSlot = driver.localSlot
            currentScene.networkLatencyMs = driver.latencyMs
            currentScene.networkOnline = driver.connected
            driver.submitLocalInput(currentScene, readTouch())
        }

        currentScene.update(tickSeconds)
    }

    /**
     * 카운트다운 소리. (계획서 §38)
     *
     * 방장이 시작을 누르면 세 번 울리고 마지막에 출발 신호가 난다. 화면의 숫자만
     * 두면 조이스틱에 손을 올려 둔 사람이 못 본다.
     */
    private fun updateCountdownAudio() {
        val ticks = netDriver?.countdownTicks ?: 0
        if (ticks <= 0) {
            // 세다가 끝났으면 출발 신호. 취소돼서 0이 된 경우와 가른다.
            if (lastCountdownSecond == 0) audio?.play(AudioDirector.Event.COUNTDOWN_GO)
            lastCountdownSecond = -1
            return
        }
        // 남은 초. 3, 2, 1 순으로 줄어든다.
        val second = (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND
        if (second == lastCountdownSecond) return
        lastCountdownSecond = second
        audio?.play(AudioDirector.Event.COUNTDOWN_TICK)
    }

    /**
     * 손가락을 게임 입력으로 옮긴다.
     *
     * 조이스틱은 아날로그로 받되 가장 가까운 네 방향으로 접는다. (계획서 §19)
     * 그 접는 일은 [TouchControls] 가 이미 해 두었으므로 여기서는 담기만 한다.
     */
    private fun readTouch(): Messages.Input {
        val state = touch.consume()
        return Messages.Input(
            tick = tick,
            direction = state.direction?.ordinal ?: Messages.Input.NO_DIRECTION,
            moving = state.moving,
            fire = state.fire,
            special = state.special,
        )
    }

    override fun onRender(alpha: Float) {
        drainCommands()
        if (!renderer.isReady) return

        val width = renderer.surfaceWidth
        val height = renderer.surfaceHeight
        if (width <= 0 || height <= 0) return

        batch.begin()
        // 설정은 혼자 화면을 다 쓴다. 뒤에 메뉴가 비쳐 보이면 어느 쪽을 누르는
        // 것인지 헷갈리고, 판 밖으로 삐져나온 글자가 설정의 일부처럼 읽힌다.
        if (overlay != Overlay.NONE) {
            when (overlay) {
                Overlay.GAME_SETTINGS -> gameSettingsScene?.render(batch)
                Overlay.SOUND -> soundSettingsScene?.render(batch)
                Overlay.NONE -> Unit
            }
            batch.end()
            finishFrame()
            return
        }

        when (screen) {
            Screen.MENU -> menuScene?.render(batch)

            Screen.ROOMS -> roomListScene?.render(batch)

            Screen.LOBBY -> lobbyScene?.let { lobby ->
                // 로비에서는 맵을 그리지 않는다. 아직 어떤 판인지 정해지지 않았다.
                netDriver?.fillLobbyView(lobby.view)
                lobby.render(batch)
            }

            Screen.BATTLE -> renderBattle(width, height)

            Screen.RESULT -> resultScene?.render(batch)
        }
        batch.end()
        finishFrame()
    }

    private fun finishFrame() {
        if (batch.droppedSprites > 0) {
            Log.w(TAG, "스프라이트 ${batch.droppedSprites}개가 배치 상한을 넘어 버려졌다")
        }
        renderer.renderFrame(batch, CLEAR_R, CLEAR_G, CLEAR_B)
    }

    private fun renderBattle(width: Int, height: Int) {
        val currentScene = scene ?: return
        // 스테이지가 새로 생성되면 맵 크기가 달라질 수 있다. 논리 해상도를 맞춰 준다.
        if (currentScene.logicalWidth != viewport.logicalWidth) {
            viewport.resizeWorld(currentScene.logicalWidth, currentScene.logicalHeight)
        }
        // 월드도 UI 와 같은 16:9 조각 안에 담는다. 월드만 화면 전체를 쓰면 가로로 긴
        // 단말에서 맵과 조작 UI 가 서로 다른 자리를 기준으로 놓인다.
        val stage = Viewport.Insets.of(StageBox.fit(width.toFloat(), height.toFloat()), width, height)
        viewport.update(width, height, insets.outerOf(stage))
        currentScene.render(batch, viewport)
        // 조작 UI 는 맵 위에, 화면 픽셀 좌표로 그린다. 맵과 함께 늘었다 줄었다 하면
        // 안 된다. 손가락 크기는 해상도가 아니라 기기 크기를 따르기 때문이다.
        controlsRenderer?.render(
            batch,
            touch,
            currentScene.tankOfSlot(currentScene.localSlot),
            currentScene.elapsed,
        )
    }

    override fun onStats(stats: GameLoop.Stats) {
        Log.d(
            TAG,
            "fps=${stats.fps} tps=${stats.ticksPerSecond} dropped=${stats.droppedTicks} " +
                "sprites=${batch.spriteCount} runs=${batch.runCount} backend=$backend screen=$screen",
        )
    }

    // -----------------------------------------------------------------------

    private fun drainCommands() {
        when (val command = pendingCommand.getAndSet(null)) {
            null -> Unit
            is SurfaceCommand.Attach -> attach(command)
            is SurfaceCommand.Resize -> {
                renderer.resize(command.width, command.height)
                resizeScenes(command.width, command.height)
            }
            SurfaceCommand.Detach -> {
                closeRoom()
                scanner?.close()
                scanner = null
                controlsRenderer = null
                lobbyScene = null
                menuScene = null
                roomListScene = null
                gameSettingsScene = null
                soundSettingsScene = null
                resultScene = null
                audio?.stopAllLoops()
                playback?.release()
                playback = null
                audio = null
                renderer.detachSurface()
                assets = null
                catalog = null
            }
        }
    }

    private var reducedEffects = false

    /**
     * 기기가 힘겨워할 만한지. (계획서 §24)
     *
     * 안드로이드가 직접 알려 주는 값이라 우리가 램을 재서 짐작하는 것보다 낫다.
     * 제조사가 저사양으로 표시한 기기에서는 시스템도 같은 기준으로 움직인다.
     */
    private fun isLowRamDevice(): Boolean = runCatching {
        context?.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }.getOrDefault(false)

    private fun attach(command: SurfaceCommand.Attach) {
        val backend = renderer.attachSurface(command.surface, preferVulkan)
        if (backend == RendererBackend.NONE) {
            Log.e(TAG, "렌더러를 초기화하지 못했다")
            return
        }
        renderer.resize(command.width, command.height)

        val source = AssetSource.of(assetManager)
        val loaded = GameAssets.load(source, renderer)
        val art = SpriteCatalog(loaded)
        assets = loaded
        catalog = art
        // 밸런스 값은 코드가 아니라 balance.json 에서 온다. (계획서 §41-19)
        balance = BalanceConfig.load(source)

        controlsRenderer = ControlsRenderer(art)
        lobbyScene = LobbyScene(art)
        menuScene = MainMenuScene(art)
        roomListScene = RoomListScene(art)
        resultScene = ResultScene(art)
        gameSettingsScene = GameSettingsScene(art)
        soundSettingsScene = SoundSettingsScene(art).apply {
            // 슬라이더를 끄는 동안 바로 들려야 고른 값이 맞는지 알 수 있다.
            onDeviceChanged = { device ->
                deviceSettings = device
                applyVolumes()
            }
        }
        resizeScenes(command.width, command.height)

        // 소리는 있으면 좋고 없어도 게임은 돈다. 컨텍스트가 없는 자리(테스트 등)에서는
        // 통째로 건너뛴다.
        val settings = AudioSettings.load(source)
        val lowRam = isLowRamDevice()
        reducedEffects = lowRam && !settings.lowRam.dashTrail
        if (lowRam) Log.i(TAG, "저사양 기기로 판단해 연출을 줄인다")
        context?.let { ctx ->
            val streams = if (lowRam) settings.lowRam.maxStreams else settings.maxStreams
            val output = AndroidPlayback(ctx, assetManager, settings, streams)
            playback = output
            audio = AudioDirector(
                settings = settings,
                playback = output,
                clock = System::currentTimeMillis,
                musicEnabled = !lowRam || settings.lowRam.bgmEnabled,
            )
            val saved = SettingsStore(ctx)
            store = saved
            deviceSettings = saved.loadDevice()
            playerName = saved.loadName(playerName)
            applyVolumes()
        }

        when {
            soloDebug -> openRoom(NetRole.LOCAL)
            netRole != NetRole.LOCAL -> openRoom(netRole)
            else -> {
                screen = Screen.MENU
                openScanner()
                audio?.setTrack(AudioDirector.Track.MENU)
            }
        }
    }

    private companion object {
        const val TAG = "BattleCity"
        const val DEFAULT_LOGICAL = 832f
        const val DEFAULT_PLAYERS = 4
        const val DEFAULT_NAME = "P1"
        const val TICKS_PER_SECOND = 60

        // 원작의 검은 배경. 맵 밖 레터박스 영역이 된다.
        const val CLEAR_R = 0.05f
        const val CLEAR_G = 0.05f
        const val CLEAR_B = 0.06f
    }
}

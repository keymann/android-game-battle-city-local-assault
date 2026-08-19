package com.kophas.battlecity.game

import android.content.res.AssetManager
import android.util.Log
import android.view.Surface
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.core.GameLoop
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.input.TouchControls
import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.render.ControlsRenderer
import com.kophas.battlecity.render.AssetSource
import com.kophas.battlecity.render.GameAssets
import com.kophas.battlecity.render.NativeRenderer
import com.kophas.battlecity.render.RendererBackend
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.Viewport
import java.util.concurrent.atomic.AtomicReference

/**
 * 서피스 수명과 게임 루프를 잇는 지점.
 *
 * Vulkan / EGL 컨텍스트는 생성한 스레드에 묶이므로, 서피스 이벤트를 UI 스레드에서
 * 즉시 처리하지 않고 명령으로 쌓아 두었다가 **게임 루프 스레드에서** 소비한다.
 */
class GameHost(private val assetManager: AssetManager) : GameLoop.Callbacks {

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

    private var controlsRenderer: ControlsRenderer? = null
    private var lobbyScene: LobbyScene? = null
    private var tick = 0L

    /** 로비를 보여 줄 차례인가. 혼자 하는 판에는 로비가 없다. */
    private fun inLobby(): Boolean = netDriver?.inLobby == true

    // --- 손가락 (계획서 §18) ---------------------------------------------
    //
    // 로비와 전투는 받는 것이 다르다. 로비는 눌린 자리만 보고, 전투는 조이스틱을
    // 끌고 다닌다. 창에서 갈라 놓으면 창이 게임 상태를 알아야 하므로 여기서 가른다.

    fun onTouchDown(pointerId: Int, x: Float, y: Float) {
        if (inLobby()) {
            handleLobbyTap(x, y)
        } else {
            touch.onDown(pointerId, x, y)
        }
    }

    fun onTouchMove(pointerId: Int, x: Float, y: Float) {
        if (!inLobby()) touch.onMove(pointerId, x, y)
    }

    fun onTouchUp(pointerId: Int) {
        if (inLobby()) lobbyScene?.onRelease() else touch.onUp(pointerId)
    }

    fun onTouchCancel() {
        if (inLobby()) lobbyScene?.onRelease() else touch.onCancel()
    }

    private fun handleLobbyTap(x: Float, y: Float) {
        val lobby = lobbyScene ?: return
        val driver = netDriver ?: return
        when (lobby.onTap(x, y)) {
            LobbyScene.Action.TOGGLE_READY -> driver.toggleReady()
            LobbyScene.Action.START -> driver.startMatch(scene)
            LobbyScene.Action.NONE -> Unit
        }
    }
    private val viewport = Viewport(DEFAULT_LOGICAL, DEFAULT_LOGICAL)
    private val loop = GameLoop(this)

    private var assets: GameAssets? = null
    private var scene: BattleScene? = null
    private var insets = Viewport.Insets.NONE

    var preferVulkan: Boolean = true

    /** 이 기기가 맡을 역할. 화면이 붙기 전에 정해야 한다. (계획서 §4.2) */
    var netRole: NetRole = NetRole.LOCAL

    /** Client 로 붙을 때 직접 지정한 Host 주소. null 이면 브로드캐스트로 찾는다. */
    var hostAddress: String? = null

    var playerName: String = "PLAYER"

    private var netDriver: NetDriver? = null

    val backend: RendererBackend get() = renderer.backend

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        pendingCommand.set(SurfaceCommand.Attach(surface, width, height))
        loop.start()
        loop.resume()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        pendingCommand.set(SurfaceCommand.Resize(width, height))
        touch.resize(width, height)
        lobbyScene?.resize(width, height)
    }

    fun onSurfaceDestroyed() {
        pendingCommand.set(SurfaceCommand.Detach)
        // 렌더 스레드가 Detach 를 소비할 기회를 준 뒤 정지시킨다.
        loop.stop()
        drainCommands()
    }

    fun onPause() = loop.pause()

    fun onResume() = loop.resume()

    fun setInsets(insets: Viewport.Insets) {
        this.insets = insets
    }

    fun release() {
        loop.stop()
        netDriver?.close()
        netDriver = null
        renderer.detachSurface()
        renderer.close()
    }

    override fun onUpdate(tickIndex: Long, tickSeconds: Float) {
        tick++
        netDriver?.onTick()

        val currentScene = scene
        val driver = netDriver
        // 로비에서는 조종 입력을 보내지 않는다. 아직 탱크가 없다.
        if (currentScene != null && driver != null && !inLobby()) {
            driver.submitLocalInput(currentScene, readTouch())
        }

        currentScene?.update(tickSeconds)
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
        val currentScene = scene ?: return
        if (!renderer.isReady) return

        val width = renderer.surfaceWidth
        val height = renderer.surfaceHeight
        if (width <= 0 || height <= 0) return
        // 스테이지가 새로 생성되면 맵 크기가 달라질 수 있다. 논리 해상도를 맞춰 준다.
        if (currentScene.logicalWidth != viewport.logicalWidth) {
            viewport.resizeWorld(currentScene.logicalWidth, currentScene.logicalHeight)
        }
        viewport.update(width, height, insets)

        batch.begin()
        if (inLobby()) {
            // 로비에서는 맵을 그리지 않는다. 아직 어떤 판인지 정해지지 않았다.
            lobbyScene?.let { lobby ->
                netDriver?.fillLobbyView(lobby.view)
                lobby.render(batch)
            }
        } else {
            currentScene.render(batch, viewport)
            // 조작 UI 는 맵 위에, 화면 픽셀 좌표로 그린다. 맵과 함께 늘었다 줄었다
            // 하면 안 된다. 손가락 크기는 해상도가 아니라 기기 크기를 따르기 때문이다.
            controlsRenderer?.render(batch, touch, currentScene.tankOfSlot(localSlot()))
        }
        batch.end()

        if (batch.droppedSprites > 0) {
            Log.w(TAG, "스프라이트 ${batch.droppedSprites}개가 배치 상한을 넘어 버려졌다")
        }
        renderer.renderFrame(batch, CLEAR_R, CLEAR_G, CLEAR_B)
    }

    override fun onStats(stats: GameLoop.Stats) {
        Log.d(
            TAG,
            "fps=${stats.fps} tps=${stats.ticksPerSecond} dropped=${stats.droppedTicks} " +
                "sprites=${batch.spriteCount} runs=${batch.runCount} backend=$backend",
        )
    }

    private fun drainCommands() {
        when (val command = pendingCommand.getAndSet(null)) {
            null -> Unit
            is SurfaceCommand.Attach -> attach(command)
            is SurfaceCommand.Resize -> {
                renderer.resize(command.width, command.height)
                touch.resize(command.width, command.height)
                lobbyScene?.resize(command.width, command.height)
            }
            SurfaceCommand.Detach -> {
                netDriver?.close()
                netDriver = null
                controlsRenderer = null
                lobbyScene = null
                renderer.detachSurface()
                assets = null
                scene = null
            }
        }
    }

    private fun localSlot(): Int = netDriver?.localSlot ?: 0

    private fun attach(command: SurfaceCommand.Attach) {
        val backend = renderer.attachSurface(command.surface, preferVulkan)
        if (backend == RendererBackend.NONE) {
            Log.e(TAG, "렌더러를 초기화하지 못했다")
            return
        }
        renderer.resize(command.width, command.height)
        touch.resize(command.width, command.height)

        val source = AssetSource.of(assetManager)
        val loaded = GameAssets.load(source, renderer)
        assets = loaded

        // 세션을 먼저 연다. 자리 배정이 씬보다 앞서야 사람이 잡은 자리에 AI 가 안 붙는다.
        val driver = NetDriver(netRole, playerName, hostAddress)
        // 이 기기를 쥔 사람의 자리에도 AI 를 붙이지 않는다.
        driver.humanSlots += driver.localSlot
        netDriver = driver
        val catalog = SpriteCatalog(loaded)
        controlsRenderer = ControlsRenderer(catalog)
        lobbyScene = LobbyScene(catalog).apply { resize(command.width, command.height) }

        // 밸런스 값은 코드가 아니라 balance.json 에서 온다. (계획서 §41-19)
        val newScene = BattleScene(
            assets = loaded,
            balance = BalanceConfig.load(source),
            role = netRole,
            humanSlots = driver.humanSlots,
        )
        driver.attachScene(newScene)
        scene = newScene
        viewport.resizeWorld(newScene.logicalWidth, newScene.logicalHeight)
    }

    private companion object {
        const val TAG = "BattleCity"
        const val DEFAULT_LOGICAL = 832f

        // 원작의 검은 배경. 맵 밖 레터박스 영역이 된다.
        const val CLEAR_R = 0.05f
        const val CLEAR_G = 0.05f
        const val CLEAR_B = 0.06f
    }
}

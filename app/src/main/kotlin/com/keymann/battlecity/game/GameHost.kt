package com.keymann.battlecity.game

import android.content.res.AssetManager
import android.util.Log
import android.view.Surface
import com.keymann.battlecity.core.GameLoop
import com.keymann.battlecity.render.AssetSource
import com.keymann.battlecity.render.GameAssets
import com.keymann.battlecity.render.NativeRenderer
import com.keymann.battlecity.render.RendererBackend
import com.keymann.battlecity.render.SpriteBatch
import com.keymann.battlecity.render.Viewport
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
    private val viewport = Viewport(DEFAULT_LOGICAL, DEFAULT_LOGICAL)
    private val loop = GameLoop(this)

    private var assets: GameAssets? = null
    private var scene: Phase1Scene? = null
    private var insets = Viewport.Insets.NONE

    var preferVulkan: Boolean = true

    val backend: RendererBackend get() = renderer.backend

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        pendingCommand.set(SurfaceCommand.Attach(surface, width, height))
        loop.start()
        loop.resume()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        pendingCommand.set(SurfaceCommand.Resize(width, height))
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
        renderer.detachSurface()
        renderer.close()
    }

    override fun onUpdate(tickIndex: Long, tickSeconds: Float) {
        scene?.update(tickSeconds)
    }

    override fun onRender(alpha: Float) {
        drainCommands()
        val currentScene = scene ?: return
        if (!renderer.isReady) return

        val width = renderer.surfaceWidth
        val height = renderer.surfaceHeight
        if (width <= 0 || height <= 0) return
        viewport.update(width, height, insets)

        batch.begin()
        currentScene.render(batch, viewport)
        batch.end()

        if (batch.droppedSprites > 0) {
            Log.w(TAG, "스프라이트 ${batch.droppedSprites}개가 배치 상한을 넘어 버려졌다")
        }
        renderer.renderFrame(batch, CLEAR_R, CLEAR_G, CLEAR_B)
    }

    override fun onStats(stats: GameLoop.Stats) {
        Log.d(TAG, "fps=${stats.fps} tps=${stats.ticksPerSecond} dropped=${stats.droppedTicks} backend=$backend")
    }

    private fun drainCommands() {
        when (val command = pendingCommand.getAndSet(null)) {
            null -> Unit
            is SurfaceCommand.Attach -> attach(command)
            is SurfaceCommand.Resize -> renderer.resize(command.width, command.height)
            SurfaceCommand.Detach -> {
                renderer.detachSurface()
                assets = null
                scene = null
            }
        }
    }

    private fun attach(command: SurfaceCommand.Attach) {
        val backend = renderer.attachSurface(command.surface, preferVulkan)
        if (backend == RendererBackend.NONE) {
            Log.e(TAG, "렌더러를 초기화하지 못했다")
            return
        }
        renderer.resize(command.width, command.height)

        val loaded = GameAssets.load(AssetSource.of(assetManager), renderer)
        assets = loaded
        val newScene = Phase1Scene(loaded)
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

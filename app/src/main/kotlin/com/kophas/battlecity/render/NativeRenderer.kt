package com.kophas.battlecity.render

import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

enum class RendererBackend(val nativeValue: Int) {
    NONE(0),
    VULKAN(1),
    GLES(2);

    companion object {
        fun fromNative(value: Int): RendererBackend =
            entries.firstOrNull { it.nativeValue == value } ?: NONE
    }
}

/**
 * 네이티브 렌더러 핸들.
 *
 * 게임 로직은 이 클래스를 통해서만 렌더러를 만지고, Vulkan / OpenGL ES 중
 * 무엇이 선택됐는지는 [backend] 로만 알 수 있다. (계획서 §41-11)
 */
class NativeRenderer : AutoCloseable {

    private var handle: Long = nativeCreate()

    var backend: RendererBackend = RendererBackend.NONE
        private set

    val isReady: Boolean get() = handle != 0L && backend != RendererBackend.NONE

    /**
     * @param preferVulkan false 면 Vulkan 을 건너뛰고 바로 GLES 로 간다(강제 폴백 검증용).
     * @return 실제로 선택된 백엔드
     */
    fun attachSurface(surface: Surface, preferVulkan: Boolean = true): RendererBackend {
        if (handle == 0L) return RendererBackend.NONE
        backend = RendererBackend.fromNative(nativeAttachSurface(handle, surface, preferVulkan))
        Log.i(TAG, "렌더러 백엔드: $backend")
        return backend
    }

    fun resize(width: Int, height: Int) {
        if (handle == 0L) return
        nativeResize(handle, width, height)
    }

    fun detachSurface() {
        if (handle == 0L) return
        nativeDetachSurface(handle)
        backend = RendererBackend.NONE
    }

    /**
     * [pixels] 는 반드시 direct ByteBuffer(RGBA8888, tightly packed)여야 한다.
     *
     * @param nearest 픽셀아트면 true. 회전하는 벡터풍 스프라이트는 false 로 둔다.
     */
    fun uploadTexture(
        textureId: Int,
        width: Int,
        height: Int,
        pixels: ByteBuffer,
        nearest: Boolean,
    ): Boolean {
        if (handle == 0L) return false
        require(pixels.isDirect) { "텍스처 픽셀은 direct ByteBuffer 여야 한다" }
        return nativeUploadTexture(handle, textureId, width, height, pixels, nearest)
    }

    fun releaseTexture(textureId: Int) {
        if (handle == 0L) return
        nativeReleaseTexture(handle, textureId)
    }

    fun renderFrame(batch: SpriteBatch, clearRed: Float, clearGreen: Float, clearBlue: Float) {
        if (handle == 0L) return
        nativeRenderFrame(
            handle,
            clearRed,
            clearGreen,
            clearBlue,
            batch.spriteBuffer,
            batch.spriteCount,
            batch.runBuffer,
            batch.runCount,
            batch.opaqueSpriteCount,
        )
    }

    val surfaceWidth: Int get() = if (handle == 0L) 0 else nativeSurfaceWidth(handle)

    val surfaceHeight: Int get() = if (handle == 0L) 0 else nativeSurfaceHeight(handle)

    override fun close() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
        backend = RendererBackend.NONE
    }

    private external fun nativeCreate(): Long
    private external fun nativeAttachSurface(handle: Long, surface: Surface, preferVulkan: Boolean): Int
    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeDetachSurface(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeUploadTexture(
        handle: Long,
        textureId: Int,
        width: Int,
        height: Int,
        pixels: ByteBuffer,
        nearest: Boolean,
    ): Boolean

    private external fun nativeReleaseTexture(handle: Long, textureId: Int)

    @Suppress("LongParameterList")
    private external fun nativeRenderFrame(
        handle: Long,
        clearR: Float,
        clearG: Float,
        clearB: Float,
        sprites: ByteBuffer,
        spriteCount: Int,
        runs: ByteBuffer,
        runCount: Int,
        opaqueCount: Int,
    )

    private external fun nativeSurfaceWidth(handle: Long): Int
    private external fun nativeSurfaceHeight(handle: Long): Int

    companion object {
        private const val TAG = "BattleCity"

        init {
            System.loadLibrary("battlecity")
        }

        /** 기기가 실제로 Vulkan 인스턴스를 만들 수 있는지. (계획서 §24) */
        @JvmStatic
        external fun nativeIsVulkanSupported(): Boolean
    }
}

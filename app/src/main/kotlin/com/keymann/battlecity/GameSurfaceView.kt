package com.keymann.battlecity

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.keymann.battlecity.game.GameHost
import com.keymann.battlecity.render.Viewport

/**
 * 게임 렌더 영역. 안드로이드 UI 와 게임 렌더링을 분리한다. (계획서 §41-12)
 *
 * `SurfaceView` 를 쓰는 이유는 네이티브가 `ANativeWindow` 를 직접 잡아
 * Vulkan / EGL 서피스를 만들어야 하기 때문이다.
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    val host = GameHost(context.assets)

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyInsets()
        if (!started) {
            started = true
            host.onSurfaceAvailable(holder.surface, width, height)
        } else {
            host.onSurfaceChanged(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        started = false
        host.onSurfaceDestroyed()
    }

    private var started = false

    private fun applyInsets() {
        val systemInsets = rootWindowInsets ?: return
        val cutout = systemInsets.displayCutout
        host.setInsets(
            Viewport.Insets(
                left = cutout?.safeInsetLeft ?: 0,
                top = cutout?.safeInsetTop ?: 0,
                right = cutout?.safeInsetRight ?: 0,
                bottom = cutout?.safeInsetBottom ?: 0,
            ),
        )
    }
}

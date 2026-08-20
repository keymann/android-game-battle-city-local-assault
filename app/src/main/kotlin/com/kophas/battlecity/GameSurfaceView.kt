package com.kophas.battlecity

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.kophas.battlecity.game.GameHost
import com.kophas.battlecity.render.Viewport

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

    val host = GameHost(context.assets, context)

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    /**
     * 손가락을 게임에 넘긴다. (계획서 §18, §19)
     *
     * `MotionEvent` 는 여기서 끝난다. 안쪽으로는 손가락 번호와 좌표만 넘어간다.
     * 그래야 조작 규칙을 기기 없이 시험할 수 있다.
     *
     * 여러 손가락을 함께 본다. 움직이면서 쏘는 것이 기본 조작이기 때문이다.
     */
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                host.onTouchDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                // 움직임은 손가락별로 따로 오지 않고 한 번에 묶여 온다.
                for (index in 0 until event.pointerCount) {
                    host.onTouchMove(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index),
                    )
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                host.onTouchUp(event.getPointerId(event.actionIndex))

            MotionEvent.ACTION_CANCEL -> host.onTouchCancel()
        }
        return true
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

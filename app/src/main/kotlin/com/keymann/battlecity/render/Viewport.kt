package com.keymann.battlecity.render

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 논리 게임 월드를 실제 화면에 맞춘다. (계획서 §21, §22)
 *
 * ```
 * Logical Game World -> Viewport -> Device Screen
 * ```
 *
 * 게임 월드의 타일 크기는 절대 바꾸지 않고 viewport 만 조정한다.
 * Battle City 는 맵 전체가 항상 보여야 하므로 CONTAIN(레터박스) 방식을 쓴다.
 * 폴더블이 펼쳐지면 [update] 만 다시 불리면 되고 논리 좌표계는 그대로다.
 */
class Viewport(
    logicalWidth: Float,
    logicalHeight: Float,
) {
    var logicalWidth: Float = logicalWidth
        private set

    var logicalHeight: Float = logicalHeight
        private set

    var screenWidth: Int = 0
        private set

    var screenHeight: Int = 0
        private set

    /** 논리 1px 이 화면에서 차지하는 픽셀 수. */
    var scale: Float = 1f
        private set

    var offsetX: Float = 0f
        private set

    var offsetY: Float = 0f
        private set

    /** 게임 월드가 실제로 차지하는 화면 영역(안전 영역 안쪽). */
    val contentWidth: Float get() = logicalWidth * scale
    val contentHeight: Float get() = logicalHeight * scale

    fun resizeWorld(width: Float, height: Float) {
        require(width > 0f && height > 0f) { "논리 해상도는 0보다 커야 한다" }
        logicalWidth = width
        logicalHeight = height
        update(screenWidth, screenHeight, lastInsets)
    }

    private var lastInsets = Insets.NONE

    /**
     * @param insets 시스템 바 / 디스플레이 컷아웃 등 피해야 할 영역
     */
    fun update(screenWidth: Int, screenHeight: Int, insets: Insets = Insets.NONE) {
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        lastInsets = insets
        if (screenWidth <= 0 || screenHeight <= 0) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            return
        }

        val availableWidth = (screenWidth - insets.left - insets.right).coerceAtLeast(1)
        val availableHeight = (screenHeight - insets.top - insets.bottom).coerceAtLeast(1)

        scale = min(availableWidth / logicalWidth, availableHeight / logicalHeight)
        offsetX = insets.left + (availableWidth - logicalWidth * scale) * 0.5f
        offsetY = insets.top + (availableHeight - logicalHeight * scale) * 0.5f
    }

    fun worldToScreenX(worldX: Float): Float = offsetX + worldX * scale

    fun worldToScreenY(worldY: Float): Float = offsetY + worldY * scale

    fun worldToScreenLength(length: Float): Float = length * scale

    fun screenToWorldX(screenX: Float): Float = (screenX - offsetX) / scale

    fun screenToWorldY(screenY: Float): Float = (screenY - offsetY) / scale

    /** 논리 좌표가 화면 안에 들어오는지. 컬링에 쓴다. */
    fun isVisible(worldX: Float, worldY: Float, width: Float, height: Float): Boolean {
        val left = worldToScreenX(worldX)
        val top = worldToScreenY(worldY)
        return left + width * scale >= 0f &&
            top + height * scale >= 0f &&
            left <= screenWidth &&
            top <= screenHeight
    }

    override fun toString(): String =
        "Viewport(logical=${logicalWidth.roundToInt()}x${logicalHeight.roundToInt()}, " +
            "screen=${screenWidth}x$screenHeight, scale=$scale, offset=($offsetX, $offsetY))"

    data class Insets(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
    ) {
        companion object {
            val NONE = Insets()
        }
    }
}

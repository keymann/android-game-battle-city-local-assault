package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants

/**
 * 전체 화면 UI 를 그리는 공용 도구. 메뉴 · 로비 · 설정 · 결과가 함께 쓴다.
 *
 * 화면마다 같은 코드를 다시 쓰지 않으려고 모았다. 판 위에 글자를 얹는 일이 매번
 * 똑같은데, 그 계산이 조금씩 어긋나면 화면마다 정렬이 달라 보인다.
 *
 * 좌표는 화면 픽셀이다. 맵이 없으므로 논리 해상도라는 것이 없다.
 */
class ScreenUi(private val catalog: SpriteCatalog) {

    /** 화면 위의 사각형. 그리는 자리이자 누르는 자리다. */
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        val centerX: Float get() = x + width * 0.5f
        val centerY: Float get() = y + height * 0.5f
        val bottom: Float get() = y + height

        operator fun contains(point: Pair<Float, Float>): Boolean =
            point.first in x..(x + width) && point.second in y..(y + height)
    }

    var width: Float = 0f
        private set

    var height: Float = 0f
        private set

    /** 한 칸. 화면 높이 기준이라 기기가 달라도 비율이 유지된다. */
    val unit: Float get() = height * UNIT_RATIO

    fun resize(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    fun rect(xRatio: Float, yRatio: Float, widthRatio: Float, heightUnits: Float): Rect =
        Rect(width * xRatio, height * yRatio, width * widthRatio, unit * heightUnits)

    /** 가로 가운데에 놓는 사각형. */
    fun centeredRect(yRatio: Float, widthRatio: Float, heightUnits: Float): Rect =
        rect((1f - widthRatio) * 0.5f, yRatio, widthRatio, heightUnits)

    /**
     * 그림 비율을 지키는 사각형. 너비를 정하면 높이가 따라온다.
     *
     * 판마다 그려진 비율이 다르다. 아무 크기나 넣으면 걸쇠가 늘어나고 모서리가
     * 뭉개져서, 같은 팩에서 온 그림인데 서로 다른 물건처럼 보인다.
     */
    fun fitByWidth(
        region: TextureRegion,
        widthRatio: Float,
        yRatio: Float,
        centerXRatio: Float = 0.5f,
    ): Rect {
        val w = width * widthRatio
        val h = w / region.aspect
        return Rect(width * centerXRatio - w * 0.5f, height * yRatio, w, h)
    }

    /** 높이를 정하면 너비가 따라온다. 세로로 자리가 빡빡한 것에 쓴다. */
    fun fitByHeight(
        region: TextureRegion,
        heightUnits: Float,
        yRatio: Float,
        centerXRatio: Float = 0.5f,
    ): Rect {
        val h = unit * heightUnits
        val w = h * region.aspect
        return Rect(width * centerXRatio - w * 0.5f, height * yRatio, w, h)
    }

    // -----------------------------------------------------------------------

    fun panel(
        batch: SpriteBatch,
        region: TextureRegion,
        rect: Rect,
        color: Int = WHITE,
        alpha: Float = 1f,
    ) {
        batch.draw(
            region = region,
            x = rect.x,
            y = rect.y,
            width = rect.width,
            height = rect.height,
            layer = Constants.Layer.HUD,
            red = red(color),
            green = green(color),
            blue = blue(color),
            alpha = alpha,
        )
    }

    /**
     * 가로로 늘어나는 판. 좌·우 끝은 그대로 두고 가운데만 늘린다.
     *
     * 띠 모양 판을 통째로 늘리면 둥근 모서리와 걸쇠가 뭉개진다. 세 조각으로 나눠
     * 가운데만 늘리면 어떤 너비에서도 끝 모양이 그대로 남는다.
     */
    fun bar(
        batch: SpriteBatch,
        region: TextureRegion,
        rect: Rect,
        color: Int = WHITE,
        alpha: Float = 1f,
    ) {
        // 끝 조각은 원래 비율대로 그린다. 그래야 늘어나 보이지 않는다.
        val cap = (rect.height * region.aspect / BAR_SLICES).coerceAtMost(rect.width * 0.5f)
        val middle = (rect.width - cap * 2f).coerceAtLeast(0f)
        val r = red(color)
        val g = green(color)
        val b = blue(color)

        batch.draw(
            region = region.sub(0, 0, BAR_SLICES, 1), x = rect.x, y = rect.y,
            width = cap, height = rect.height, layer = Constants.Layer.HUD,
            red = r, green = g, blue = b, alpha = alpha,
        )
        if (middle > 0f) {
            batch.draw(
                region = region.sub(1, 0, BAR_SLICES, 1), x = rect.x + cap, y = rect.y,
                width = middle, height = rect.height, layer = Constants.Layer.HUD,
                red = r, green = g, blue = b, alpha = alpha,
            )
        }
        batch.draw(
            region = region.sub(2, 0, BAR_SLICES, 1), x = rect.x + cap + middle, y = rect.y,
            width = cap, height = rect.height, layer = Constants.Layer.HUD,
            red = r, green = g, blue = b, alpha = alpha,
        )
    }

    fun icon(
        batch: SpriteBatch,
        region: TextureRegion,
        centerX: Float,
        centerY: Float,
        size: Float,
        color: Int = WHITE,
        alpha: Float = 1f,
    ) {
        batch.draw(
            region = region,
            x = centerX - size * 0.5f,
            y = centerY - size * 0.5f,
            width = size,
            height = size,
            layer = Constants.Layer.HUD,
            red = red(color),
            green = green(color),
            blue = blue(color),
            alpha = alpha,
        )
    }

    fun text(
        batch: SpriteBatch,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int = WHITE,
        alpha: Float = 1f,
    ) {
        var cursor = x
        for (char in value) {
            catalog.glyph(char)?.let { glyph ->
                batch.draw(
                    region = glyph,
                    x = cursor,
                    y = y,
                    width = size,
                    height = size,
                    // 판과 같은 층에 그린다. 아래층에 두면 판이 글자를 덮는다.
                    layer = Constants.Layer.HUD,
                    red = red(color),
                    green = green(color),
                    blue = blue(color),
                    alpha = alpha,
                )
            }
            cursor += size * TextLayout.ADVANCE
        }
    }

    fun centered(
        batch: SpriteBatch,
        value: String,
        centerX: Float,
        y: Float,
        size: Float,
        color: Int = WHITE,
        alpha: Float = 1f,
    ) {
        if (value.isEmpty()) return
        text(batch, value, TextLayout.leftForCenter(value, centerX, size), y, size, color, alpha)
    }

    /**
     * 판 그림의 **속판**에 글자를 맞춰 놓는다.
     *
     * 그린 사각형의 한가운데가 곧 글자 자리가 아니다. 판 그림에는 위아래 테두리와
     * 걸쇠가 들어 있고 두께가 서로 달라서, 글자가 들어가야 할 자리는 사각형
     * 한가운데와 다르다. 글자가 판보다 길면 줄여서 넣는다.
     */
    fun label(
        batch: SpriteBatch,
        value: String,
        rect: Rect,
        size: Float,
        color: Int = WHITE,
        centerRatio: Float = 0.5f,
        widthRatio: Float = 0.8f,
        alpha: Float = 1f,
    ) {
        if (value.isEmpty()) return
        val fitted = TextLayout.fit(value, size, rect.width * widthRatio)
        centered(batch, value, rect.centerX, rect.y + rect.height * centerRatio - fitted * 0.5f, fitted, color, alpha)
    }

    fun hits(rect: Rect, x: Float, y: Float): Boolean = (x to y) in rect

    private fun red(color: Int): Float = ((color ushr 16) and 0xFF) / 255f

    private fun green(color: Int): Float = ((color ushr 8) and 0xFF) / 255f

    private fun blue(color: Int): Float = (color and 0xFF) / 255f

    companion object {
        const val WHITE = 0xFFFFFF
        const val DIM = 0x8A93A0
        const val ACCENT = 0xF5A623
        const val CYAN = 0x35D2F0
        const val GREEN = 0x8BE04B
        const val RED = 0xE2543C

        private const val UNIT_RATIO = 0.07f

        /** 띠 판을 나누는 조각 수. 좌 · 가운데 · 우. */
        private const val BAR_SLICES = 3
    }
}

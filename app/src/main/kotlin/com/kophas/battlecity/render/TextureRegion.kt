package com.kophas.battlecity.render

/**
 * 아틀라스 안의 사각 영역.
 *
 * [u0]..[v1] 은 이미 half-texel 인셋이 적용된 값이다.
 * 선형 필터링으로 이웃 스프라이트 색이 새어 나오는 것(bleeding)을 막기 위한 것으로,
 * 아틀라스가 여백 없이 패킹된 asset2 tiny 타일맵에서 특히 중요하다.
 */
data class TextureRegion(
    val name: String,
    val textureId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float,
) {
    val aspect: Float get() = width.toFloat() / height

    /**
     * 이 영역을 [cols] x [rows] 로 쪼갠 조각.
     *
     * 벽돌/강철을 셀 단위로 부술 때 쓴다. 스프라이트 한 장을 블록에 걸쳐 놓고
     * 살아남은 셀만 자기 사분면을 그리면, 원작처럼 일부만 무너진 벽이 된다.
     */
    fun sub(col: Int, row: Int, cols: Int, rows: Int): TextureRegion {
        require(col in 0 until cols && row in 0 until rows) { "조각 좌표가 범위를 벗어났다" }
        val stepU = (u1 - u0) / cols
        val stepV = (v1 - v0) / rows
        return copy(
            name = "$name#$col$row",
            width = width / cols,
            height = height / rows,
            u0 = u0 + stepU * col,
            v0 = v0 + stepV * row,
            u1 = u0 + stepU * (col + 1),
            v1 = v0 + stepV * (row + 1),
        )
    }

    companion object {
        const val DEFAULT_INSET_TEXELS: Float = 0.5f

        fun of(
            name: String,
            textureId: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            textureWidth: Int,
            textureHeight: Int,
            insetTexels: Float = DEFAULT_INSET_TEXELS,
        ): TextureRegion {
            // 인셋이 영역보다 커지지 않도록 조인다(1px 스프라이트 방어).
            val insetX = minOf(insetTexels, width / 2f - 0.01f).coerceAtLeast(0f)
            val insetY = minOf(insetTexels, height / 2f - 0.01f).coerceAtLeast(0f)
            return TextureRegion(
                name = name,
                textureId = textureId,
                x = x,
                y = y,
                width = width,
                height = height,
                u0 = (x + insetX) / textureWidth,
                v0 = (y + insetY) / textureHeight,
                u1 = (x + width - insetX) / textureWidth,
                v1 = (y + height - insetY) / textureHeight,
            )
        }
    }
}

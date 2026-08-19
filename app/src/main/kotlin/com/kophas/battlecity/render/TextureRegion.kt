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

    /**
     * 아래에서 [ratio] 만큼만 남긴 가로 띠.
     *
     * 쿨타임 고리를 밑에서부터 차오르게 그릴 때 쓴다. 원형 마스크를 쓰려면 셰이더가
     * 필요한데, 스프라이트 배치에는 그런 것이 없다. 같은 그림을 어둡게 한 번,
     * 밝게 잘라서 한 번 그리면 셰이더 없이도 연속으로 차오른다.
     */
    fun bottomBand(ratio: Float): TextureRegion {
        val keep = ratio.coerceIn(0f, 1f)
        if (keep >= 1f) return this
        val cut = (v1 - v0) * (1f - keep)
        return copy(
            name = "$name#band",
            height = maxOf(1, (height * keep).toInt()),
            v0 = v0 + cut,
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

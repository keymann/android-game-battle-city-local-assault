package com.keymann.battlecity.render

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

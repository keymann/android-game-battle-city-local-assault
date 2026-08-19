package com.keymann.battlecity.render

/**
 * 여백 0으로 패킹된 격자 타일맵(asset2 `tilemap_packed.png`)용 아틀라스.
 *
 * 인덱스만으로 UV 가 결정된다.
 * ```
 * col = index % columns
 * row = index / columns
 * ```
 */
class GridAtlas(
    val textureId: Int,
    val tileSize: Int,
    val columns: Int,
    val rows: Int,
    val spacing: Int = 0,
    val textureWidth: Int = columns * tileSize + (columns - 1) * spacing,
    val textureHeight: Int = rows * tileSize + (rows - 1) * spacing,
    private val insetTexels: Float = TextureRegion.DEFAULT_INSET_TEXELS,
) {
    val tileCount: Int = columns * rows

    private val cache = arrayOfNulls<TextureRegion>(tileCount)

    operator fun get(index: Int): TextureRegion {
        require(index in 0 until tileCount) {
            "타일 인덱스 $index 가 범위(0..${tileCount - 1})를 벗어났다"
        }
        cache[index]?.let { return it }

        val col = index % columns
        val row = index / columns
        val region = TextureRegion.of(
            name = "tile_$index",
            textureId = textureId,
            x = col * (tileSize + spacing),
            y = row * (tileSize + spacing),
            width = tileSize,
            height = tileSize,
            textureWidth = textureWidth,
            textureHeight = textureHeight,
            insetTexels = insetTexels,
        )
        cache[index] = region
        return region
    }
}

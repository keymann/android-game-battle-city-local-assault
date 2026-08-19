package com.kophas.battlecity.render

/**
 * Kenney TextureAtlas(XML) 리더.
 *
 * ```xml
 * <TextureAtlas imagePath="main.png">
 *     <SubTexture name="tank_blue.png" x="0" y="0" width="42" height="46"/>
 * </TextureAtlas>
 * ```
 *
 * 안드로이드 `XmlPullParser` 를 쓰지 않는다. 이 포맷은 속성만 있는 단순 구조이고,
 * 프레임워크 의존을 빼면 JVM 단위 테스트에서 실제 아틀라스로 검증할 수 있다.
 */
class TextureAtlas private constructor(
    val textureId: Int,
    val imagePath: String,
    val textureWidth: Int,
    val textureHeight: Int,
    private val regions: Map<String, TextureRegion>,
) {
    val names: Set<String> get() = regions.keys

    val size: Int get() = regions.size

    operator fun get(name: String): TextureRegion =
        regions[name] ?: error("아틀라스에 '$name' 스프라이트가 없다 (textureId=$textureId)")

    fun find(name: String): TextureRegion? = regions[name]

    fun contains(name: String): Boolean = regions.containsKey(name)

    companion object {
        private val SUB_TEXTURE = Regex(
            """<SubTexture\s+name="([^"]+)"\s+x="(-?\d+)"\s+y="(-?\d+)"\s+width="(\d+)"\s+height="(\d+)"""",
        )
        private val IMAGE_PATH = Regex("""<TextureAtlas\s+imagePath="([^"]+)"""")

        /**
         * @param textureWidth 실제 PNG 크기. UV 계산에 필요하므로 호출자가 넘긴다.
         */
        fun parse(
            xml: String,
            textureId: Int,
            textureWidth: Int,
            textureHeight: Int,
            insetTexels: Float = TextureRegion.DEFAULT_INSET_TEXELS,
        ): TextureAtlas {
            require(textureWidth > 0 && textureHeight > 0) { "텍스처 크기가 유효하지 않다" }

            val imagePath = IMAGE_PATH.find(xml)?.groupValues?.get(1)
                ?: error("TextureAtlas 루트 태그를 찾지 못했다")

            val regions = LinkedHashMap<String, TextureRegion>()
            for (match in SUB_TEXTURE.findAll(xml)) {
                val (rawName, x, y, w, h) = match.destructured
                // Kenney 는 name 에 확장자를 포함한다. 게임 코드에서는 확장자 없이 쓴다.
                val name = rawName.removeSuffix(".png")
                regions[name] = TextureRegion.of(
                    name = name,
                    textureId = textureId,
                    x = x.toInt(),
                    y = y.toInt(),
                    width = w.toInt(),
                    height = h.toInt(),
                    textureWidth = textureWidth,
                    textureHeight = textureHeight,
                    insetTexels = insetTexels,
                )
            }
            check(regions.isNotEmpty()) { "SubTexture 를 하나도 찾지 못했다" }

            return TextureAtlas(textureId, imagePath, textureWidth, textureHeight, regions)
        }
    }
}

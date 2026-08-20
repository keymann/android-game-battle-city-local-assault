package com.kophas.battlecity.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 인게임 아트를 디코드해 네이티브에 올린다. (docs/ASSET_SELECTION.md)
 *
 * 텍스처는 **한 장**이다. 지형·탱크·이펙트·HUD 가 모두 같은 팩에서 나오고 화풍이
 * 같아서 나눌 이유가 없다. 한 장이면 프레임 안에서 텍스처 교체가 한 번도 없다.
 *
 * 탱크는 4방향이 미리 그려져 있어 런타임 회전이 없다. 회전이 없으니 전부 NEAREST 로
 * 뽑아 도트를 그대로 살린다.
 *
 * 반드시 렌더 스레드에서 호출해야 한다. EGL 컨텍스트가 스레드에 묶여 있기 때문이다.
 */
class GameAssets private constructor(
    val manifest: AssetManifest,
    /** 랜덤 맵 생성 규칙. (assets/RANDOM_MAP_ASSET_GUIDE.md) */
    val mapGen: com.kophas.battlecity.map.MapGenProfile,
    val atlas: TextureAtlas,
) {
    operator fun get(name: String): TextureRegion =
        atlas.find(name) ?: error("아틀라스에 '$name' 스프라이트가 없다")

    fun find(name: String): TextureRegion? = atlas.find(name)

    companion object {
        private const val TAG = "BattleCity"

        const val TEXTURE_GAME = 1

        fun load(source: AssetSource, renderer: NativeRenderer): GameAssets {
            val manifest = AssetManifest.load(source)
            val mapGen = com.kophas.battlecity.map.MapGenProfile.load(source)
            val atlas = loadAtlas(source, renderer, manifest, "game", TEXTURE_GAME)
            Log.i(TAG, "에셋 로드 완료: 스프라이트 ${atlas.size}장, 맵 규칙 v${mapGen.version}")
            return GameAssets(manifest, mapGen, atlas)
        }

        private fun loadAtlas(
            source: AssetSource,
            renderer: NativeRenderer,
            manifest: AssetManifest,
            key: String,
            textureId: Int,
        ): TextureAtlas {
            val spec = manifest.atlases[key] ?: error("매니페스트에 $key 아틀라스가 없다")
            val nearest = spec.filter != "linear"

            val bitmap = decode(source, spec.image)
            upload(renderer, textureId, bitmap, nearest)
            val atlas = TextureAtlas.parse(
                xml = source.readText(spec.descriptor ?: error("$key 아틀라스에 descriptor 가 없다")),
                textureId = textureId,
                textureWidth = bitmap.width,
                textureHeight = bitmap.height,
                // NEAREST 로 뽑는 픽셀아트는 인셋을 거의 두지 않는다. 크게 두면 도트가 잘린다.
                // LINEAR 아틀라스는 이웃 스프라이트가 번지지 않도록 half-texel 을 둔다.
                insetTexels = if (nearest) 0.02f else 0.5f,
            )
            bitmap.recycle()
            return atlas
        }

        private fun decode(source: AssetSource, path: String): Bitmap {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                // 셰이더가 일반(non-premultiplied) 알파 블렌딩을 쓴다.
                inPremultiplied = false
            }
            return source.open(path).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
                    ?: error("이미지를 디코드하지 못했다: $path")
            }
        }

        private fun upload(
            renderer: NativeRenderer,
            textureId: Int,
            bitmap: Bitmap,
            nearest: Boolean,
        ) {
            val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            check(renderer.uploadTexture(textureId, bitmap.width, bitmap.height, buffer, nearest)) {
                "텍스처 업로드 실패 (id=$textureId)"
            }
        }
    }
}

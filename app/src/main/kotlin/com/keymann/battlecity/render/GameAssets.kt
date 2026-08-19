package com.keymann.battlecity.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 선별된 두 장의 아틀라스를 디코드해 네이티브에 올린다.
 * (docs/ASSET_SELECTION.md §2 - 런타임 텍스처 2장)
 *
 * 반드시 렌더 스레드에서 호출해야 한다. EGL 컨텍스트가 스레드에 묶여 있기 때문이다.
 */
class GameAssets private constructor(
    val manifest: AssetManifest,
    val main: TextureAtlas,
    val tiny: GridAtlas,
) {
    companion object {
        private const val TAG = "BattleCity"

        const val TEXTURE_MAIN = 1
        const val TEXTURE_TINY = 2

        fun load(source: AssetSource, renderer: NativeRenderer): GameAssets {
            val manifest = AssetManifest.load(source)

            val mainSpec = manifest.atlases["main"] ?: error("매니페스트에 main 아틀라스가 없다")
            val tinySpec = manifest.atlases["tiny"] ?: error("매니페스트에 tiny 아틀라스가 없다")

            val mainBitmap = decode(source, mainSpec.image)
            upload(renderer, TEXTURE_MAIN, mainBitmap)
            val mainAtlas = TextureAtlas.parse(
                xml = source.readText(
                    mainSpec.descriptor ?: error("main 아틀라스에 descriptor 가 없다"),
                ),
                textureId = TEXTURE_MAIN,
                textureWidth = mainBitmap.width,
                textureHeight = mainBitmap.height,
            )
            val mainWidth = mainBitmap.width
            val mainHeight = mainBitmap.height
            mainBitmap.recycle()

            val tinyBitmap = decode(source, tinySpec.image)
            upload(renderer, TEXTURE_TINY, tinyBitmap)
            val tinyAtlas = GridAtlas(
                textureId = TEXTURE_TINY,
                tileSize = tinySpec.tileSize,
                columns = tinySpec.columns,
                rows = tinySpec.rows,
                spacing = tinySpec.spacing,
                textureWidth = tinyBitmap.width,
                textureHeight = tinyBitmap.height,
            )
            tinyBitmap.recycle()

            Log.i(
                TAG,
                "에셋 로드 완료: main ${mainWidth}x$mainHeight / ${mainAtlas.size} 스프라이트, " +
                    "tiny ${tinyAtlas.tileCount} 타일",
            )
            return GameAssets(manifest, mainAtlas, tinyAtlas)
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

        private fun upload(renderer: NativeRenderer, textureId: Int, bitmap: Bitmap) {
            val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            check(renderer.uploadTexture(textureId, bitmap.width, bitmap.height, buffer)) {
                "텍스처 업로드 실패 (id=$textureId)"
            }
        }
    }
}

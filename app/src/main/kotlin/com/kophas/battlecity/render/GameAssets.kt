package com.kophas.battlecity.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 인게임 아트를 디코드해 네이티브에 올린다. (docs/ASSET_SELECTION.md)
 *
 * 텍스처는 두 장이고 샘플링 방식이 다르다.
 *  - `tiles`  16px 픽셀아트(지형·구조물·환경·HUD). **NEAREST** 로 도트를 살린다.
 *  - `units`  Top-down Tanks Redux(탱크·포탄·폭발). 회전하므로 **LINEAR**.
 *
 * 스프라이트는 이름으로 조회하고, 이름 앞자리가 출처를 알려 준다.
 *   `battle_` `town_` `dungeon_` `ui_` `fx_` = 픽셀 아틀라스, 그 외 = 유닛 아틀라스
 *
 * 반드시 렌더 스레드에서 호출해야 한다. EGL 컨텍스트가 스레드에 묶여 있기 때문이다.
 */
class GameAssets private constructor(
    val manifest: AssetManifest,
    val tiles: TextureAtlas,
    val units: TextureAtlas,
) {
    /** 이름이 어느 아틀라스 것인지 몰라도 찾아 준다. */
    operator fun get(name: String): TextureRegion =
        tiles.find(name) ?: units.find(name)
            ?: error("어느 아틀라스에도 '$name' 스프라이트가 없다")

    fun find(name: String): TextureRegion? = tiles.find(name) ?: units.find(name)

    companion object {
        private const val TAG = "BattleCity"

        const val TEXTURE_TILES = 1
        const val TEXTURE_UNITS = 2

        fun load(source: AssetSource, renderer: NativeRenderer): GameAssets {
            val manifest = AssetManifest.load(source)

            val tiles = loadAtlas(source, renderer, manifest, "tiles", TEXTURE_TILES)
            val units = loadAtlas(source, renderer, manifest, "units", TEXTURE_UNITS)

            Log.i(TAG, "에셋 로드 완료: tiles ${tiles.size} / units ${units.size} 스프라이트")
            return GameAssets(manifest, tiles, units)
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

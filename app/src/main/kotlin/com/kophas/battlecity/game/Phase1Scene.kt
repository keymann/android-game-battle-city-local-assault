package com.kophas.battlecity.game

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.render.GameAssets
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.TextureRegion
import com.kophas.battlecity.render.Viewport
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Phase 1 검증용 씬.
 *
 * 목적은 게임플레이가 아니라 파이프라인 전체가 살아 있는지 눈으로 확인하는 것이다.
 *  - 아틀라스 2장이 모두 올라갔는가 (main 지형/유닛 + tiny 물/HUD)
 *  - 레이어 정렬과 텍스처 run 배칭이 동작하는가
 *  - 고정 timestep 이 렌더 FPS 와 분리되어 도는가
 *  - Viewport 가 화면 비율/폴더블 변화를 흡수하는가
 *
 * Phase 2 에서 실제 TileMap 으로 대체된다.
 */
class Phase1Scene(private val assets: GameAssets) {

    private val blockPx = assets.manifest.world.blockPx
    private val mapBlocks = 13
    val logicalWidth: Float = mapBlocks * blockPx
    val logicalHeight: Float = mapBlocks * blockPx

    private val random = Random(SEED)

    /** 배경 지형은 매 프레임 다시 뽑지 않는다. 시드 고정이라 실행마다 동일하다. */
    private val ground: Array<TextureRegion> = Array(mapBlocks * mapBlocks) {
        assets.main[GRASS_TILES[random.nextInt(GRASS_TILES.size)]]
    }

    private val waterTile = assets.tiny[WATER_INDEX]
    private val heartTile = assets.tiny[assets.manifest.hudLifeIndex]

    private val bodies = listOf("tankBody_blue", "tankBody_green", "tankBody_red", "tankBody_sand")
    private val barrels = listOf(
        "tankBlue_barrel3",
        "tankGreen_barrel2",
        "tankRed_barrel1",
        "tankSand_barrel2",
    )

    private var elapsedSeconds = 0f

    /** 고정 틱에서만 상태를 바꾼다. (계획서 §41-9) */
    fun update(tickSeconds: Float) {
        elapsedSeconds += tickSeconds
    }

    fun render(batch: SpriteBatch, viewport: Viewport) {
        drawGround(batch, viewport)
        drawWater(batch, viewport)
        drawTanks(batch, viewport)
        drawHud(batch, viewport)
    }

    private fun drawGround(batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(blockPx)
        for (row in 0 until mapBlocks) {
            for (col in 0 until mapBlocks) {
                batch.draw(
                    region = ground[row * mapBlocks + col],
                    x = viewport.worldToScreenX(col * blockPx),
                    y = viewport.worldToScreenY(row * blockPx),
                    width = size,
                    height = size,
                    layer = Constants.Layer.GROUND,
                )
            }
        }
    }

    private fun drawWater(batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(blockPx)
        for (row in 5..6) {
            for (col in 2..10) {
                batch.draw(
                    region = waterTile,
                    x = viewport.worldToScreenX(col * blockPx),
                    y = viewport.worldToScreenY(row * blockPx),
                    width = size,
                    height = size,
                    layer = Constants.Layer.HAZARD,
                )
            }
        }
    }

    private fun drawTanks(batch: SpriteBatch, viewport: Viewport) {
        val tankSize = viewport.worldToScreenLength(assets.manifest.world.tankSizePx)
        bodies.forEachIndexed { index, bodyName ->
            val phase = elapsedSeconds * 0.8f + index * (PI.toFloat() / 2f)
            val worldX = (2f + index * 3f) * blockPx
            val worldY = (9f + sin(phase) * 1.5f) * blockPx
            val screenX = viewport.worldToScreenX(worldX)
            val screenY = viewport.worldToScreenY(worldY)
            val rotation = phase % (2f * PI.toFloat())

            val body = assets.main[bodyName]
            val bodyWidth = tankSize
            val bodyHeight = tankSize * (body.height.toFloat() / body.width)
            batch.draw(
                region = body,
                x = screenX,
                y = screenY,
                width = bodyWidth,
                height = bodyHeight,
                layer = Constants.Layer.ENTITY,
                rotation = rotation,
            )

            // 포신은 몸체와 분리 렌더한다. 이동 방향과 조준 방향을 따로 돌리기 위한 구조다.
            // 두 스프라이트가 같은 점(탱크 중심)을 축으로 돌아야 어긋나지 않는다.
            //   - 몸체: origin (0.5, 0.5) -> 자기 사각형의 중심
            //   - 포신: origin (0.5, 1.0) -> 아래쪽 끝(포미)이 탱크 중심에 오도록 배치
            val centerX = screenX + bodyWidth * 0.5f
            val centerY = screenY + bodyHeight * 0.5f
            val barrel = assets.main[barrels[index]]
            val barrelScale = bodyWidth / body.width
            val barrelWidth = barrel.width * barrelScale
            val barrelHeight = barrel.height * barrelScale
            batch.draw(
                region = barrel,
                x = centerX - barrelWidth * 0.5f,
                y = centerY - barrelHeight,
                width = barrelWidth,
                height = barrelHeight,
                layer = Constants.Layer.ENTITY,
                rotation = rotation,
                originX = 0.5f,
                originY = 1.0f,
            )
        }
    }

    private fun drawHud(batch: SpriteBatch, viewport: Viewport) {
        val heartSize = viewport.worldToScreenLength(blockPx * 0.5f)
        repeat(HUD_LIVES) { index ->
            batch.draw(
                region = heartTile,
                x = viewport.worldToScreenX(blockPx * 0.25f) + index * heartSize * 1.1f,
                y = viewport.worldToScreenY(blockPx * 0.25f),
                width = heartSize,
                height = heartSize,
                layer = Constants.Layer.HUD,
            )
        }
    }

    private companion object {
        const val SEED = 20260819L
        const val WATER_INDEX = 37
        const val HUD_LIVES = 3

        val GRASS_TILES = arrayOf(
            "tileGrass1",
            "tileGrass2",
            "tileGrass_roadNorth",
            "tileGrass_roadEast",
            "tileGrass_roadCrossing",
        )
    }
}

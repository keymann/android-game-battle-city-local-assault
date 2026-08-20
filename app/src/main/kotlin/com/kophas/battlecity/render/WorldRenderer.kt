package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.Projectile
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.TileType

/**
 * [GameWorld] 를 [SpriteBatch] 로 옮긴다.
 *
 * 게임 상태를 절대 바꾸지 않는다. 읽기만 한다. (계획서 §41-1)
 * 레이어 순서는 docs/STAGE_GENERATION.md §5 를 그대로 따른다.
 */
class WorldRenderer(
    private val catalog: SpriteCatalog,
) {
    private var stage: StageData? = null
    private var groundRegions: Array<TextureRegion> = emptyArray()
    private var groundTints: IntArray = IntArray(0)
    private var objectRegions: Array<TextureRegion> = emptyArray()

    /** 스테이지가 바뀌면 스프라이트 이름과 지형 틴트를 다시 해석한다. */
    fun bind(stage: StageData) {
        this.stage = stage
        groundRegions = catalog.resolveAll(stage.groundNames)
        groundTints = IntArray(stage.groundNames.size) { catalog.groundTintFor(stage.groundNames[it]) }
        objectRegions = catalog.resolveAll(stage.spriteNames)
    }

    fun render(world: GameWorld, batch: SpriteBatch, viewport: Viewport, timeSeconds: Float) {
        val stage = this.stage ?: return
        drawGround(stage, batch, viewport)
        drawDecor(stage, batch, viewport)
        drawCells(world, batch, viewport, timeSeconds)
        drawBase(world, stage, batch, viewport)
        drawTanks(world, batch, viewport)
        drawProjectiles(world, batch, viewport)
        drawExplosions(world, batch, viewport)
    }

    // -----------------------------------------------------------------------

    private fun drawGround(stage: StageData, batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(Constants.BLOCK_PX)
        for (by in 0 until stage.blocksY) {
            for (bx in 0 until stage.blocksX) {
                val index = stage.ground[by * stage.blocksX + bx].toInt()
                val tint = groundTints[index]
                batch.draw(
                    region = groundRegions[index],
                    x = viewport.worldToScreenX(bx * Constants.BLOCK_PX),
                    y = viewport.worldToScreenY(by * Constants.BLOCK_PX),
                    width = size,
                    height = size,
                    layer = Constants.Layer.GROUND,
                    red = red(tint),
                    green = green(tint),
                    blue = blue(tint),
                )
            }
        }
    }

    private fun drawDecor(stage: StageData, batch: SpriteBatch, viewport: Viewport) {
        val tint = catalog.decorTint
        for (decor in stage.decor) {
            // 캐노피(나무)는 전경이라 원색을 유지하고, 바닥 장식만 눌러 준다.
            val muted = decor.layer == Constants.Layer.DECAL
            batch.draw(
                region = objectRegions[decor.spriteIndex.toInt()],
                x = viewport.worldToScreenX(decor.x),
                y = viewport.worldToScreenY(decor.y),
                width = viewport.worldToScreenLength(decor.width),
                height = viewport.worldToScreenLength(decor.height),
                layer = decor.layer,
                rotation = decor.rotation,
                red = if (muted) red(tint) else 1f,
                green = if (muted) green(tint) else 1f,
                blue = if (muted) blue(tint) else 1f,
                alpha = decor.alpha,
            )
        }
    }

    /**
     * 셀 단위 오브젝트.
     *
     * 벽돌/강철은 스프라이트 한 장을 블록에 걸쳐 놓고 각 셀이 자기 사분면만 그린다.
     * 그래서 절반만 부서진 벽이 원작처럼 자연스럽게 남는다.
     */
    private fun drawCells(
        world: GameWorld,
        batch: SpriteBatch,
        viewport: Viewport,
        timeSeconds: Float,
    ) {
        val map = world.map
        val cellSize = viewport.worldToScreenLength(Constants.CELL_PX)
        val perBlock = Constants.CELLS_PER_BLOCK

        val waterFrame = catalog.waterFrames[
            ((timeSeconds * catalog.waterAnimFps).toInt()) % catalog.waterFrames.size,
        ]
        val iceAlpha = ((catalog.iceTint ushr 24) and 0xFF) / 255f
        val iceRed = ((catalog.iceTint ushr 16) and 0xFF) / 255f
        val iceGreen = ((catalog.iceTint ushr 8) and 0xFF) / 255f
        val iceBlue = (catalog.iceTint and 0xFF) / 255f

        // 블록(2x2 셀)이 통째로 같은 타일이면 쿼드 하나로 합쳐 그린다.
        // 벽이 부서져 일부만 남았을 때만 셀 단위로 쪼갠다. (계획서 §25.1)
        val blockSize = viewport.worldToScreenLength(Constants.BLOCK_PX)

        for (by in 0 until map.cellsY / perBlock) {
            for (bx in 0 until map.cellsX / perBlock) {
                val originX = bx * perBlock
                val originY = by * perBlock

                var uniformType: TileType? = map.typeAt(originX, originY)
                var uniformSprite = map.spriteIndexAt(originX, originY)
                for (dy in 0 until perBlock) {
                    for (dx in 0 until perBlock) {
                        if (map.typeAt(originX + dx, originY + dy) != uniformType ||
                            map.spriteIndexAt(originX + dx, originY + dy) != uniformSprite
                        ) {
                            uniformType = null
                        }
                    }
                }

                if (uniformType != null) {
                    drawTile(
                        type = uniformType,
                        spriteIndex = uniformSprite,
                        quadrantX = -1,
                        quadrantY = -1,
                        screenX = viewport.worldToScreenX(originX * Constants.CELL_PX),
                        screenY = viewport.worldToScreenY(originY * Constants.CELL_PX),
                        size = blockSize,
                        batch = batch,
                        waterFrame = waterFrame,
                        iceRed = iceRed,
                        iceGreen = iceGreen,
                        iceBlue = iceBlue,
                        iceAlpha = iceAlpha,
                    )
                    continue
                }

                for (dy in 0 until perBlock) {
                    for (dx in 0 until perBlock) {
                        val cx = originX + dx
                        val cy = originY + dy
                        drawTile(
                            type = map.typeAt(cx, cy),
                            spriteIndex = map.spriteIndexAt(cx, cy),
                            quadrantX = dx,
                            quadrantY = dy,
                            screenX = viewport.worldToScreenX(cx * Constants.CELL_PX),
                            screenY = viewport.worldToScreenY(cy * Constants.CELL_PX),
                            size = cellSize,
                            batch = batch,
                            waterFrame = waterFrame,
                            iceRed = iceRed,
                            iceGreen = iceGreen,
                            iceBlue = iceBlue,
                            iceAlpha = iceAlpha,
                        )
                    }
                }
            }
        }
    }

    /** [quadrantX] 가 -1 이면 블록 전체를 한 장으로 그린다. */
    @Suppress("LongParameterList")
    private fun drawTile(
        type: TileType,
        spriteIndex: Int,
        quadrantX: Int,
        quadrantY: Int,
        screenX: Float,
        screenY: Float,
        size: Float,
        batch: SpriteBatch,
        waterFrame: TextureRegion,
        iceRed: Float,
        iceGreen: Float,
        iceBlue: Float,
        iceAlpha: Float,
    ) {
        when (type) {
            TileType.WATER -> batch.draw(
                region = waterFrame,
                x = screenX,
                y = screenY,
                width = size,
                height = size,
                layer = Constants.Layer.HAZARD,
            )

            TileType.ICE -> batch.draw(
                region = catalog.iceFrame,
                x = screenX,
                y = screenY,
                width = size,
                height = size,
                layer = Constants.Layer.HAZARD,
                red = iceRed,
                green = iceGreen,
                blue = iceBlue,
                alpha = iceAlpha,
            )

            TileType.BRICK, TileType.STEEL -> {
                if (spriteIndex < 0) return
                val full = objectRegions[spriteIndex]
                val region = if (quadrantX < 0) {
                    full
                } else {
                    full.sub(quadrantX, quadrantY, Constants.CELLS_PER_BLOCK, Constants.CELLS_PER_BLOCK)
                }
                batch.draw(
                    region = region,
                    x = screenX,
                    y = screenY,
                    width = size,
                    height = size,
                    layer = Constants.Layer.OBJECT,
                )
            }

            else -> Unit
        }
    }

    private fun drawBase(
        world: GameWorld,
        stage: StageData,
        batch: SpriteBatch,
        viewport: Viewport,
    ) {
        val (px, py) = stage.blockToPx(stage.baseBlock)
        val size = viewport.worldToScreenLength(Constants.BLOCK_PX)
        // 파괴되면 적기가 백기로 바뀐다. 규칙 변화가 그림 하나로 읽힌다.
        val region = if (world.map.baseDestroyed) catalog.baseDestroyed else catalog.baseIntact
        batch.draw(
            region = region,
            x = viewport.worldToScreenX(px),
            y = viewport.worldToScreenY(py),
            width = size,
            height = size,
            layer = Constants.Layer.OBJECT,
        )
    }

    private fun drawTanks(world: GameWorld, batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(Tank.SIZE)

        for (tank in world.tanks) {
            if (!tank.alive) continue

            // 숲에 들어간 탱크는 캐노피에 가려진다. 반투명으로 살짝만 비친다.
            val concealed = world.map.conceals(tank.x, tank.y, Tank.SIZE, Tank.SIZE)
            val alpha = if (concealed) CONCEALED_ALPHA else 1f

            val screenX = viewport.worldToScreenX(tank.x)
            val screenY = viewport.worldToScreenY(tank.y)
            val rotation = tank.direction.radians

            val body = catalog.bodyOf(tank)
            val bodyHeight = size * (body.height.toFloat() / body.width)

            // 지형과 탱크 색이 같은 계열이어도 형태가 읽히도록 어두운 실루엣을 깐다.
            // 같은 레이어 / 같은 텍스처라 배치 안에서 삽입 순서가 그대로 유지된다.
            catalog.outlineOf(body)?.let { outline ->
                val scale = catalog.silhouetteScale
                val outlineWidth = size * scale
                val outlineHeight = bodyHeight * scale
                val silhouette = catalog.silhouetteColor
                batch.draw(
                    region = outline,
                    x = screenX - (outlineWidth - size) * 0.5f,
                    y = screenY - (outlineHeight - bodyHeight) * 0.5f,
                    width = outlineWidth,
                    height = outlineHeight,
                    layer = Constants.Layer.ENTITY,
                    rotation = rotation,
                    red = red(silhouette),
                    green = green(silhouette),
                    blue = blue(silhouette),
                    alpha = alpha * SILHOUETTE_ALPHA,
                )
            }

            batch.draw(
                region = body,
                x = screenX,
                y = screenY,
                width = size,
                height = bodyHeight,
                layer = Constants.Layer.ENTITY,
                rotation = rotation,
                alpha = alpha,
            )

            // 몸체와 포신이 같은 점(탱크 중심)을 축으로 돌아야 어긋나지 않는다.
            val centerX = screenX + size * 0.5f
            val centerY = screenY + bodyHeight * 0.5f
            val barrel = catalog.barrelOf(tank)
            val scale = size / body.width
            val barrelWidth = barrel.width * scale
            val barrelHeight = barrel.height * scale
            batch.draw(
                region = barrel,
                x = centerX - barrelWidth * 0.5f,
                y = centerY - barrelHeight,
                width = barrelWidth,
                height = barrelHeight,
                layer = Constants.Layer.ENTITY,
                rotation = rotation,
                originX = 0.5f,
                originY = 1f,
                alpha = alpha,
            )

            // 스폰 무적 동안 깜빡인다.
            if (tank.spawnGuardRemaining > 0f) {
                val blink = ((tank.spawnGuardRemaining * 8f).toInt() % 2) == 0
                if (blink) {
                    batch.draw(
                        region = body,
                        x = screenX,
                        y = screenY,
                        width = size,
                        height = bodyHeight,
                        layer = Constants.Layer.OVERLAY,
                        rotation = rotation,
                        alpha = 0.45f,
                    )
                }
            }
        }
    }

    private fun drawProjectiles(world: GameWorld, batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(Projectile.SIZE * 2.5f)
        for (projectile in world.projectiles.active) {
            if (!projectile.active) continue
            val owner = world.tanks.firstOrNull { it.id == projectile.ownerId }
            val region = owner?.let { catalog.bulletOf(it) } ?: continue
            batch.draw(
                region = region,
                x = viewport.worldToScreenX(projectile.centerX) - size * 0.5f,
                y = viewport.worldToScreenY(projectile.centerY) - size * 0.5f,
                width = size,
                height = size * (region.height.toFloat() / region.width),
                layer = Constants.Layer.ENTITY,
                rotation = projectile.direction.radians,
            )
        }
    }

    private fun drawExplosions(world: GameWorld, batch: SpriteBatch, viewport: Viewport) {
        for (explosion in world.explosions.active) {
            if (!explosion.active) continue
            val frameCount = catalog.explosionFrameCount(explosion.kind)
            val index = if (explosion.kind == com.kophas.battlecity.gameplay.Explosion.Kind.SPAWN) {
                // 스폰은 역재생이라 연기가 모여드는 것처럼 보인다.
                frameCount - 1 - explosion.frameIndex.coerceIn(0, frameCount - 1)
            } else {
                explosion.frameIndex
            }
            val size = viewport.worldToScreenLength(explosion.size)
            batch.draw(
                region = catalog.explosionFrame(explosion.kind, index),
                x = viewport.worldToScreenX(explosion.x),
                y = viewport.worldToScreenY(explosion.y),
                width = size,
                height = size,
                layer = Constants.Layer.EFFECT,
            )
        }
    }

    private fun red(color: Int): Float = ((color ushr 16) and 0xFF) / 255f

    private fun green(color: Int): Float = ((color ushr 8) and 0xFF) / 255f

    private fun blue(color: Int): Float = (color and 0xFF) / 255f

    private companion object {
        const val CONCEALED_ALPHA = 0.35f
        const val SILHOUETTE_ALPHA = 0.85f
    }
}

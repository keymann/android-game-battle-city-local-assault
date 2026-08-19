package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.BalanceConfig
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
    private var objectRegions: Array<TextureRegion> = emptyArray()

    /** 본진이 파괴된 시각. 파괴 애니메이션은 게임 상태가 아니라 연출이라 여기서 센다. */
    private var baseDestroyedAt: Float = -1f

    /** 스테이지가 바뀌면 스프라이트 이름을 다시 해석한다. */
    fun bind(stage: StageData) {
        this.stage = stage
        baseDestroyedAt = -1f
        groundRegions = catalog.resolveAll(stage.groundNames)
        objectRegions = catalog.resolveAll(stage.spriteNames)
    }

    fun render(world: GameWorld, batch: SpriteBatch, viewport: Viewport, timeSeconds: Float) {
        val stage = this.stage ?: return
        drawGround(stage, batch, viewport)
        drawDecor(stage, batch, viewport)
        drawCells(world, batch, viewport, timeSeconds)
        drawBase(world, stage, batch, viewport, timeSeconds)
        drawTanks(world, batch, viewport, timeSeconds)
        drawProjectiles(world, batch, viewport)
        drawExplosions(world, batch, viewport)
    }

    // -----------------------------------------------------------------------

    private fun drawGround(stage: StageData, batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(Constants.BLOCK_PX)
        for (by in 0 until stage.blocksY) {
            for (bx in 0 until stage.blocksX) {
                val index = stage.ground[by * stage.blocksX + bx].toInt()
                batch.draw(
                    region = groundRegions[index],
                    x = viewport.worldToScreenX(bx * Constants.BLOCK_PX),
                    y = viewport.worldToScreenY(by * Constants.BLOCK_PX),
                    width = size,
                    height = size,
                    layer = Constants.Layer.GROUND,
                )
            }
        }
    }

    private fun drawDecor(stage: StageData, batch: SpriteBatch, viewport: Viewport) {
        for (decor in stage.decor) {
            batch.draw(
                region = objectRegions[decor.spriteIndex.toInt()],
                x = viewport.worldToScreenX(decor.x),
                y = viewport.worldToScreenY(decor.y),
                width = viewport.worldToScreenLength(decor.width),
                height = viewport.worldToScreenLength(decor.height),
                layer = decor.layer,
                rotation = decor.rotation,
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

        val waterFrame = catalog.water
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
                        waterBrightness = waterBrightness(originX, originY, timeSeconds),
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
                            waterBrightness = waterBrightness(cx, cy, timeSeconds),
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
        waterBrightness: Float,
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
                red = waterBrightness,
                green = waterBrightness,
                blue = waterBrightness,
            )

            TileType.ICE -> batch.draw(
                region = catalog.ice,
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

    /**
     * 본진 건물.
     *
     * 스프라이트가 블록보다 세로로 길다(깃발). 바닥을 블록 하단에 맞추고
     * 위로 삐져나오게 그려야 건물이 땅에 서 있는 것처럼 보인다.
     */
    private fun drawBase(
        world: GameWorld,
        stage: StageData,
        batch: SpriteBatch,
        viewport: Viewport,
        timeSeconds: Float,
    ) {
        val (px, py) = stage.blockToPx(stage.baseBlock)
        val width = viewport.worldToScreenLength(Constants.BLOCK_PX)

        val region = if (world.map.baseDestroyed) {
            if (baseDestroyedAt < 0f) baseDestroyedAt = timeSeconds
            val frames = catalog.baseDestroyFrames
            val index = ((timeSeconds - baseDestroyedAt) * catalog.baseDestroyFps).toInt()
            // 재생이 끝나면 잔해로 남는다.
            if (index >= frames.size) catalog.baseWreck else frames[index]
        } else {
            catalog.baseIntact
        }

        val height = width * (region.height.toFloat() / region.width)
        batch.draw(
            region = region,
            x = viewport.worldToScreenX(px),
            // 블록 하단에 바닥을 맞춘다.
            y = viewport.worldToScreenY(py + Constants.BLOCK_PX) - height,
            width = width,
            height = height,
            layer = Constants.Layer.OBJECT,
        )
    }

    private fun drawTanks(
        world: GameWorld,
        batch: SpriteBatch,
        viewport: Viewport,
        timeSeconds: Float,
    ) {
        val size = viewport.worldToScreenLength(Tank.SIZE)

        for (tank in world.tanks) {
            if (!tank.alive) continue

            // 숲에 들어간 탱크는 캐노피에 가려진다. 반투명으로 살짝만 비친다.
            val concealed = world.map.conceals(tank.x, tank.y, Tank.SIZE, Tank.SIZE)
            val alpha = if (concealed) CONCEALED_ALPHA else 1f

            val screenX = viewport.worldToScreenX(tank.x)
            val screenY = viewport.worldToScreenY(tank.y)
            val rotation = tank.direction.radians + catalog.tankRotationOffset

            val body = catalog.bodyOf(tank)
            val bodyHeight = size * (body.height.toFloat() / body.width)

            // 지형과 색이 겹쳐도 형태가 읽히도록 어두운 실루엣을 먼저 깐다.
            // 같은 레이어 / 같은 텍스처라 배치 안에서 삽입 순서가 그대로 유지된다.
            catalog.outlineOf(tank)?.let { outline ->
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
                    alpha = alpha * catalog.silhouetteAlpha,
                )
            }

            // 대시 잔상은 몸체 뒤에 깔아야 진행 방향이 읽힌다. (계획서 §6.3)
            if (tank.specialActive && tank.special == BalanceConfig.Special.DASH) {
                drawDashTrail(batch, tank, body, screenX, screenY, size, bodyHeight, rotation, viewport)
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

            // 방어막은 탱크를 감싸는 청색 오라로 보여 준다. (계획서 §6.2)
            if (tank.specialActive && tank.special == BalanceConfig.Special.SHIELD) {
                drawShieldAura(batch, tank, screenX, screenY, size, bodyHeight, rotation, timeSeconds)
            }

            // 몸체와 포신이 같은 점(탱크 중심)을 축으로 돌아야 어긋나지 않는다.
            //   몸체: origin (0.5, 0.5)  포신: origin (0.5, 1.0) 으로 포미를 중심에 둔다
            val centerX = screenX + size * 0.5f
            val centerY = screenY + bodyHeight * 0.5f
            val barrel = catalog.barrelOf(tank)
            val barrelScale = size / body.width
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
                originY = 1f,
                alpha = alpha,
            )

            // 스폰 무적 동안 깜빡인다.
            if (tank.spawnGuardRemaining > 0f && ((tank.spawnGuardRemaining * 8f).toInt() % 2) == 0) {
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

    /** 진행 반대 방향으로 잔상을 남겨 속도를 보여 준다. */
    @Suppress("LongParameterList")
    private fun drawDashTrail(
        batch: SpriteBatch,
        tank: Tank,
        body: TextureRegion,
        screenX: Float,
        screenY: Float,
        width: Float,
        height: Float,
        rotation: Float,
        viewport: Viewport,
    ) {
        val fx = catalog.dashFx
        val spacing = viewport.worldToScreenLength(fx.spacingPx)
        for (i in 1..fx.afterImages) {
            val fade = fx.alpha * (1f - i.toFloat() / (fx.afterImages + 1))
            batch.draw(
                region = body,
                x = screenX - tank.direction.dx * spacing * i,
                y = screenY - tank.direction.dy * spacing * i,
                width = width,
                height = height,
                layer = Constants.Layer.ENTITY,
                rotation = rotation,
                red = red(fx.tint),
                green = green(fx.tint),
                blue = blue(fx.tint),
                alpha = fade,
            )
        }
    }

    /** 탱크 외곽선을 키우고 청색으로 물들여 맥동시킨다. */
    @Suppress("LongParameterList")
    private fun drawShieldAura(
        batch: SpriteBatch,
        tank: Tank,
        screenX: Float,
        screenY: Float,
        width: Float,
        height: Float,
        rotation: Float,
        timeSeconds: Float,
    ) {
        val outline = catalog.outlineOf(tank) ?: return
        val fx = catalog.shieldFx
        val pulse = 0.75f + 0.25f * kotlin.math.sin(timeSeconds * fx.pulseHz * TWO_PI)
        val auraWidth = width * fx.scale
        val auraHeight = height * fx.scale

        batch.draw(
            region = outline,
            x = screenX - (auraWidth - width) * 0.5f,
            y = screenY - (auraHeight - height) * 0.5f,
            width = auraWidth,
            height = auraHeight,
            layer = Constants.Layer.OVERLAY,
            rotation = rotation,
            red = red(fx.tint),
            green = green(fx.tint),
            blue = blue(fx.tint),
            alpha = fx.alpha * pulse,
        )
    }

    private fun drawProjectiles(world: GameWorld, batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(Projectile.SIZE * 2.5f)
        for (projectile in world.projectiles.active) {
            if (!projectile.active) continue
            val owner = world.tanks.firstOrNull { it.id == projectile.ownerId }

            val region = if (projectile.piercing) {
                catalog.piercingBullet
            } else {
                owner?.let { catalog.bulletOf(it) } ?: continue
            }

            // 관통탄은 뒤로 꼬리를 달아 일반 포탄과 확실히 구분한다. (계획서 §6.1)
            if (projectile.piercing) {
                val trail = catalog.piercingTrail
                val trailSize = size * 1.4f
                batch.draw(
                    region = trail,
                    x = viewport.worldToScreenX(projectile.centerX) -
                        projectile.direction.dx * size - trailSize * 0.5f,
                    y = viewport.worldToScreenY(projectile.centerY) -
                        projectile.direction.dy * size - trailSize * 0.5f,
                    width = trailSize,
                    height = trailSize * (trail.height.toFloat() / trail.width),
                    layer = Constants.Layer.ENTITY,
                    rotation = projectile.direction.radians,
                    red = red(catalog.piercingTint),
                    green = green(catalog.piercingTint),
                    blue = blue(catalog.piercingTint),
                    alpha = catalog.piercingTrailAlpha,
                )
            }

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

            val art = catalog.effectOf(explosion.kind)
            val index = explosion.frameIndex.coerceIn(0, art.frames.lastIndex)
            val size = viewport.worldToScreenLength(explosion.size)

            batch.draw(
                region = art.frames[index],
                x = viewport.worldToScreenX(explosion.x),
                y = viewport.worldToScreenY(explosion.y),
                width = size,
                height = size,
                layer = Constants.Layer.EFFECT,
            )
        }
    }

    /** 셀 위치에 따라 위상을 어긋뜨려 대각선으로 잔물결이 지나가게 한다. */
    private fun waterBrightness(cellX: Int, cellY: Int, timeSeconds: Float): Float {
        val shimmer = catalog.waterShimmer
        if (shimmer <= 0f) return 1f
        val phase = timeSeconds * catalog.waterAnimFps * TWO_PI + (cellX + cellY) * RIPPLE_STEP
        return 1f - shimmer * 0.5f + shimmer * 0.5f * kotlin.math.sin(phase)
    }

    private fun red(color: Int): Float = ((color ushr 16) and 0xFF) / 255f

    private fun green(color: Int): Float = ((color ushr 8) and 0xFF) / 255f

    private fun blue(color: Int): Float = (color and 0xFF) / 255f

    private companion object {
        const val CONCEALED_ALPHA = 0.35f
        const val TWO_PI = 6.2831855f
        const val RIPPLE_STEP = 0.55f
    }
}

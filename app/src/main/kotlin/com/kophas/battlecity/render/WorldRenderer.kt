package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.Explosion
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

    /** 저사양 기기에서 켠다. 없어도 게임이 되는 연출만 끈다. (계획서 §24) */
    var reducedEffects: Boolean = false

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

        val waterFrame = catalog.waterFrames[
            ((timeSeconds * catalog.waterAnimFps).toInt().coerceAtLeast(0)) %
                catalog.waterFrames.size,
        ]
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
                        cellX = originX,
                        cellY = originY,
                    )
                    continue
                }

                for (dy in 0 until perBlock) {
                    for (dx in 0 until perBlock) {
                        val cx = originX + dx
                        val cy = originY + dy
                        // 한 칸짜리 소품은 사분면으로 쪼개면 4분의 1만 보인다.
                        val whole = map.isWholeSprite(cx, cy)
                        drawTile(
                            type = map.typeAt(cx, cy),
                            spriteIndex = map.spriteIndexAt(cx, cy),
                            quadrantX = if (whole) -1 else dx,
                            quadrantY = if (whole) -1 else dy,
                            screenX = viewport.worldToScreenX(cx * Constants.CELL_PX),
                            screenY = viewport.worldToScreenY(cy * Constants.CELL_PX),
                            size = cellSize,
                            batch = batch,
                            waterFrame = waterFrame,
                            cellX = cx,
                            cellY = cy,
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
        cellX: Int,
        cellY: Int,
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

            // 얼음은 전용 타일이 4종이라 물들이지 않는다. 어느 칸에 어떤 무늬가
            // 오는지는 좌표로 정한다. 저장할 필요도, 주고받을 필요도 없다.
            TileType.ICE -> batch.draw(
                region = catalog.iceTiles[
                    ((cellX * 31 + cellY * 17) % catalog.iceTiles.size + catalog.iceTiles.size) %
                        catalog.iceTiles.size,
                ],
                x = screenX,
                y = screenY,
                width = size,
                height = size,
                layer = Constants.Layer.HAZARD,
            )

            // 숲은 탱크 **위**에 그려 가린다. 통과는 되지만 안이 안 보인다. (계획서 §30)
            TileType.FOREST -> {
                if (spriteIndex < 0) return
                val full = objectRegions[spriteIndex]
                batch.draw(
                    region = if (quadrantX < 0) {
                        full
                    } else {
                        full.sub(quadrantX, quadrantY, Constants.CELLS_PER_BLOCK, Constants.CELLS_PER_BLOCK)
                    },
                    x = screenX,
                    y = screenY,
                    width = size,
                    height = size,
                    layer = Constants.Layer.CANOPY,
                )
            }

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

        val region = when {
            world.map.baseDestroyed -> {
                if (baseDestroyedAt < 0f) baseDestroyedAt = timeSeconds
                val frames = catalog.baseDestroyFrames
                val index = ((timeSeconds - baseDestroyedAt) * catalog.baseDestroyFps).toInt()
                // 재생이 끝나면 잔해로 남는다.
                if (index >= frames.size) catalog.baseWreck else frames[index]
            }

            // 보호막이 남아 있으면 한눈에 보여야 한다. 한 발을 견딜 수 있다는 뜻이다.
            world.map.baseShielded -> catalog.baseShielded

            // 이미 맞았다면 그것부터 보여 준다. 다음 한 발이 마지막일 수 있다.
            world.map.baseDamaged -> catalog.baseDamaged

            // 적이 코앞이면 깜빡여 경고한다.
            baseInDanger(world) -> {
                val frames = catalog.baseWarnFrames
                frames[((timeSeconds * catalog.baseWarnFps).toInt()) % frames.size]
            }

            else -> catalog.baseIntact
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

    /**
     * 탱크.
     *
     * 방향별 그림이 따로 있어 **회전값이 없다.** 픽셀아트를 돌리면 가장자리가
     * 뭉개지는데, 미리 그려 둔 4장을 골라 쓰면 그 문제가 통째로 사라진다.
     *
     * 스프라이트 캔버스는 차체보다 넓다. 포신이 밖으로 뻗기 때문이다. 그래서
     * 탱크가 차지하는 칸이 아니라 **중심을 기준으로** 캔버스 크기만큼 그린다.
     */
    /** 본진 가까이 적이 왔는가. 경고 표시에 쓴다. */
    private fun baseInDanger(world: GameWorld): Boolean {
        val (baseX, baseY) = world.baseCellCenter()
        return world.tanks.any { tank ->
            tank.alive && tank.faction == Tank.Faction.ENEMY &&
                kotlin.math.abs(tank.centerX - baseX) < BASE_ALERT_PX &&
                kotlin.math.abs(tank.centerY - baseY) < BASE_ALERT_PX
        }
    }

    private fun drawTanks(
        world: GameWorld,
        batch: SpriteBatch,
        viewport: Viewport,
        timeSeconds: Float,
    ) {
        val spriteSize = viewport.worldToScreenLength(catalog.tankSpritePx)
        val bodySize = viewport.worldToScreenLength(Tank.SIZE)

        for (tank in world.tanks) {
            if (!tank.alive) continue

            // 숲에 들어간 탱크는 캐노피에 가려진다. 반투명으로 살짝만 비친다.
            val concealed = world.map.conceals(tank.x, tank.y, Tank.SIZE, Tank.SIZE)
            val alpha = if (concealed) CONCEALED_ALPHA else 1f

            val centerX = viewport.worldToScreenX(tank.centerX)
            val centerY = viewport.worldToScreenY(tank.centerY)

            if (!reducedEffects && tank.specialActive && tank.special == BalanceConfig.Special.DASH) {
                drawDashTrail(batch, tank, centerX, centerY, spriteSize, timeSeconds)
            }

            // 탱크 그림은 무채색이다. 고른 색을 여기서 곱한다. COM 은 단일 색이다.
            val color = catalog.palette.colorOf(tank.colorSlot)
            batch.draw(
                region = catalog.tankSprite(tank),
                x = centerX - spriteSize * 0.5f,
                y = centerY - spriteSize * 0.5f,
                width = spriteSize,
                height = spriteSize,
                layer = Constants.Layer.ENTITY,
                red = red(color),
                green = green(color),
                blue = blue(color),
                alpha = alpha,
            )

            if (tank.specialActive && tank.special == BalanceConfig.Special.SHIELD) {
                drawShield(batch, centerX, centerY, spriteSize, timeSeconds)
            }

            if (tank.faction == Tank.Faction.PLAYER) {
                drawSlotMarker(batch, tank, centerX, centerY, bodySize, alpha)
            }
        }
    }

    /**
     * 플레이어 머리 위 표식.
     *
     * 탱크 색은 **종류**가 정한다. 같은 종류를 고른 두 사람은 색이 같아서 구별되지
     * 않으므로, 슬롯 색을 입힌 작은 표식을 띄운다.
     */
    private fun drawSlotMarker(
        batch: SpriteBatch,
        tank: Tank,
        centerX: Float,
        centerY: Float,
        bodySize: Float,
        alpha: Float,
    ) {
        val marker = catalog.slotMarker
        val size = bodySize * marker.sizeRatio
        val color = catalog.palette.colorOf(tank.colorSlot)
        batch.draw(
            region = marker.region,
            x = centerX - size * 0.5f,
            y = centerY + bodySize * marker.offsetRatio,
            width = size,
            height = size,
            layer = Constants.Layer.OVERLAY,
            red = red(color),
            green = green(color),
            blue = blue(color),
            alpha = alpha,
        )
    }

    /** 대시 잔상. 진행 반대쪽에 깔아 속도가 눈에 보이게 한다. (계획서 §6.3) */
    private fun drawDashTrail(
        batch: SpriteBatch,
        tank: Tank,
        centerX: Float,
        centerY: Float,
        size: Float,
        timeSeconds: Float,
    ) {
        val region = catalog.dashFx.at(timeSeconds)
        // 탱크보다 작게, 뒤로만 깐다. 같은 크기로 겹치면 탱크가 아예 안 보인다.
        val trail = size * DASH_SIZE_RATIO
        for (i in 1..DASH_AFTER_IMAGES) {
            val back = size * DASH_SPACING_RATIO * i
            batch.draw(
                region = region,
                x = centerX - tank.direction.dx * back - trail * 0.5f,
                y = centerY - tank.direction.dy * back - trail * 0.5f,
                width = trail,
                height = trail,
                layer = Constants.Layer.EFFECT,
                alpha = DASH_ALPHA / i,
            )
        }
    }

    /** 방어막. 탱크를 감싸는 보호막이 지속 시간 동안 맥동한다. (계획서 §6.2) */
    private fun drawShield(
        batch: SpriteBatch,
        centerX: Float,
        centerY: Float,
        size: Float,
        timeSeconds: Float,
    ) {
        val region = catalog.shieldFx.at(timeSeconds)
        val shieldSize = size * SHIELD_SCALE
        batch.draw(
            region = region,
            x = centerX - shieldSize * 0.5f,
            y = centerY - shieldSize * 0.5f,
            width = shieldSize,
            height = shieldSize,
            layer = Constants.Layer.EFFECT,
            alpha = SHIELD_ALPHA,
        )
    }

    /** 포탄도 방향별로 그려져 있다. 회전시키지 않는다. */
    private fun drawProjectiles(world: GameWorld, batch: SpriteBatch, viewport: Viewport) {
        val size = viewport.worldToScreenLength(Projectile.SIZE * PROJECTILE_SPRITE_SCALE)
        for (projectile in world.projectiles.active) {
            if (!projectile.active) continue
            batch.draw(
                region = catalog.shellSprite(projectile.direction, projectile.piercing),
                x = viewport.worldToScreenX(projectile.centerX) - size * 0.5f,
                y = viewport.worldToScreenY(projectile.centerY) - size * 0.5f,
                width = size,
                height = size,
                layer = Constants.Layer.ENTITY,
            )
        }
    }

    private fun drawExplosions(world: GameWorld, batch: SpriteBatch, viewport: Viewport) {
        for (explosion in world.explosions.active) {
            if (!explosion.active) continue

            // 총구 화염만 프레임이 아니라 방향으로 고른다.
            val region = if (explosion.kind == Explosion.Kind.MUZZLE) {
                catalog.muzzleOf(explosion.direction)
            } else {
                val art = catalog.effectOf(explosion.kind)
                art.frames[explosion.frameIndex.coerceIn(0, art.frames.lastIndex)]
            }
            val size = viewport.worldToScreenLength(explosion.size)

            batch.draw(
                region = region,
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

        /** 본진에서 이 거리 안에 적이 있으면 깜빡인다. */
        const val BASE_ALERT_PX = 64f * 4f

        /** 포탄 그림은 실제 판정 크기보다 크게 그린다. 작으면 눈에 안 띈다. */
        const val PROJECTILE_SPRITE_SCALE = 3f

        const val DASH_AFTER_IMAGES = 3
        const val DASH_SIZE_RATIO = 0.62f
        const val DASH_SPACING_RATIO = 0.3f
        const val DASH_ALPHA = 0.5f

        const val SHIELD_SCALE = 1.15f
        const val SHIELD_ALPHA = 0.85f
    }
}

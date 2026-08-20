package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.TileMap
import com.kophas.battlecity.map.TileType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * 게임 상태와 규칙. (계획서 §5, §31, §41-1)
 *
 * 안드로이드 의존이 전혀 없다. 렌더러도, 네트워크도 모른다.
 * 오직 고정 틱 단위로 상태를 굴리며, 모든 판정이 결정론적이다. (계획서 §41-4)
 * Phase 6 에서 Host 가 이 클래스를 권위 있게 굴리고 결과만 내려보낸다.
 */
class GameWorld(
    stage: StageData,
    private val balance: BalanceConfig,
    private val config: Config = Config(),
) {
    data class Config(
        val maxProjectiles: Int = 64,
        val maxExplosions: Int = 48,
        val maxTanks: Int = 24,
        val projectileSpeed: Float = Constants.BLOCK_PX * 7f,
        /** 얼음 마찰. 초당 남는 속도 비율이라 작을수록 빨리 멈춘다. */
        val iceFriction: Float = 0.12f,
        /** 폭발성 프롭 기본 반경(셀). 매니페스트 값이 없을 때 쓴다. */
        val defaultBlastCells: Int = 2,
    )

    /** 규칙 판정 결과를 밖으로 알린다. 사운드/점수/네트워크가 여기에 붙는다. */
    interface Listener {
        fun onBrickDestroyed(cellX: Int, cellY: Int) = Unit
        fun onProjectileHit(x: Float, y: Float) = Unit

        /** HP 가 깎였지만 아직 살아 있다. (계획서 §12, §13) */
        fun onTankDamaged(tank: Tank, amount: Int, attackerId: Int) = Unit

        fun onTankDestroyed(tank: Tank, killerId: Int) = Unit

        /** 탱크가 나왔다. 소리와 연출이 여기에 붙는다. */
        fun onTankSpawned(tank: Tank) = Unit

        /** 포탄이 나갔다. */
        fun onFired(tank: Tank) = Unit

        /** 강철에 튕겼다. 부수지 못했다는 것을 알린다. */
        fun onSteelHit(x: Float, y: Float) = Unit

        /** 특수기를 발동했다. (계획서 §6) */
        fun onSpecialActivated(tank: Tank, special: BalanceConfig.Special) = Unit

        /** 지속형 특수기가 끝났다. */
        fun onSpecialExpired(tank: Tank, special: BalanceConfig.Special) = Unit

        fun onBaseDestroyed() = Unit
    }

    val map: TileMap = TileMap(stage)
    val stage: StageData get() = map.stage

    val tanks: MutableList<Tank> = ArrayList(config.maxTanks)
    val projectiles = ObjectPool(config.maxProjectiles) { Projectile(it) }
    val explosions = ObjectPool(config.maxExplosions) { Explosion(it) }

    var listener: Listener? = null

    var gameOver: Boolean = false
        private set

    private var nextTankId = 0
    private val tankPool = ObjectPool(config.maxTanks) { Tank(it) }

    // -----------------------------------------------------------------------
    // 스폰
    // -----------------------------------------------------------------------

    /**
     * 능력치는 전부 [BalanceConfig] 에서 읽어 채운다. 호출자가 숫자를 넘기지 않는다.
     * (계획서 §7 중요 구현 원칙)
     */
    fun spawnTank(
        faction: Tank.Faction,
        type: Tank.Type,
        colorSlot: Int,
        blockIndex: Int,
        direction: Direction,
        ownerSlot: Int = -1,
    ): Tank? {
        val tank = tankPool.obtain() ?: return null
        val stats = balance.statsFor(faction, type)
        val (px, py) = stage.blockToPx(blockIndex)

        tank.faction = faction
        tank.type = type
        tank.colorSlot = colorSlot
        tank.ownerSlot = ownerSlot
        tank.attackPower = stats.attackPower
        tank.defensePower = stats.defensePower
        tank.maxHp = balance.rules.maxHp
        tank.moveSpeed = balance.units.moveSpeedOf(stats.moveSpeedRank)
        tank.fireCooldown = balance.units.fireCooldownOf(stats.fireRateRank)
        tank.special = stats.special
        tank.specialCooldown = stats.specialCooldownSeconds
        tank.specialDuration = stats.specialDurationSeconds
        tank.shieldDamageReduction = stats.damageReduction
        tank.dashSpeedMultiplier = stats.speedMultiplier
        tank.spawnAt(px, py, direction)
        tank.spawnGuardRemaining = balance.rules.spawnGuardSeconds

        tanks += tank
        nextTankId++
        spawnExplosion(Explosion.Kind.SPAWN, tank.centerX, tank.centerY, Tank.SIZE)
        listener?.onTankSpawned(tank)
        return tank
    }

    /**
     * Host 가 보낸 탱크를 그대로 받아 놓는다. (계획서 §35 Host -> Client)
     *
     * Client 는 규칙을 굴리지 않는다. 능력치를 다시 계산하지도, 스폰 연출을 내지도
     * 않는다. 화면에 그릴 것을 세계에 앉히기만 한다. id 는 Host 가 정한 것을 쓴다.
     * 그래야 다음 스냅샷에서 같은 탱크를 알아본다.
     */
    fun adoptRemoteTank(
        id: Int,
        faction: Tank.Faction,
        type: Tank.Type,
        ownerSlot: Int,
        x: Float,
        y: Float,
        direction: Direction,
    ): Tank? {
        val tank = tanks.firstOrNull { it.id == id } ?: tankPool.obtainAt(id) ?: return null
        if (tank !in tanks) tanks += tank
        tank.faction = faction
        tank.type = type
        tank.colorSlot = ownerSlot
        tank.ownerSlot = ownerSlot
        tank.maxHp = balance.rules.maxHp
        tank.spawnAt(x, y, direction)
        tank.spawnGuardRemaining = 0f
        return tank
    }

    fun despawn(tank: Tank) {
        tank.alive = false
        tanks.remove(tank)
        tankPool.release(tank)
    }

    // -----------------------------------------------------------------------
    // 틱
    // -----------------------------------------------------------------------

    fun update(deltaSeconds: Float) {
        if (gameOver) return

        for (tank in tanks) {
            if (!tank.alive) continue
            if (tank.spawnGuardRemaining > 0f) tank.spawnGuardRemaining -= deltaSeconds
            if (tank.fireCooldownRemaining > 0f) tank.fireCooldownRemaining -= deltaSeconds
            updateSpecial(tank, deltaSeconds)
            moveTank(tank, deltaSeconds)
        }

        updateProjectiles(deltaSeconds)

        for (explosion in explosions.active) explosion.update(deltaSeconds)
        explosions.releaseIf { it.finished.also { done -> if (done) it.active = false } }

        if (map.baseDestroyed && !gameOver) {
            gameOver = true
            listener?.onBaseDestroyed()
        }
    }

    /**
     * 연출만 흘려보낸다. Client 가 쓴다. (계획서 §4.2)
     *
     * 이동도 충돌도 하지 않는다. 그것은 Host 가 정하고 스냅샷으로 내려 준다.
     * 폭발 애니메이션까지 멈추면 화면이 죽은 것처럼 보이므로 그것만 굴린다.
     */
    fun updateEffectsOnly(deltaSeconds: Float) {
        for (explosion in explosions.active) explosion.update(deltaSeconds)
        explosions.releaseIf { it.finished.also { done -> if (done) it.active = false } }
    }

    // -----------------------------------------------------------------------
    // 이동 (계획서 §31 충돌 우선순위: Tile -> Tank)
    // -----------------------------------------------------------------------

    /**
     * 방향을 바꾼다.
     *
     * 수직 방향으로 꺾을 때는 반대 축을 셀 격자에 맞춘다. 원작에서 좁은 통로에
     * 정확히 들어갈 수 있는 이유가 이 스냅이다. 스냅한 자리가 막혀 있으면 되돌린다.
     */
    fun steer(tank: Tank, direction: Direction) {
        if (tank.direction == direction) return
        val turningPerpendicular = direction.isHorizontal != tank.direction.isHorizontal
        tank.direction = direction
        if (!turningPerpendicular) return

        val originalX = tank.x
        val originalY = tank.y
        if (direction.isHorizontal) {
            tank.y = snapToCell(tank.y)
        } else {
            tank.x = snapToCell(tank.x)
        }
        if (blocked(tank, tank.x, tank.y)) {
            tank.x = originalX
            tank.y = originalY
        }
    }

    private fun moveTank(tank: Tank, deltaSeconds: Float) {
        val slip = map.slipFactor(tank.x, tank.y, Tank.SIZE, Tank.SIZE)

        var velocityX: Float
        var velocityY: Float
        if (tank.moving) {
            velocityX = tank.direction.dx * tank.effectiveMoveSpeed
            velocityY = tank.direction.dy * tank.effectiveMoveSpeed
            tank.slideX = velocityX
            tank.slideY = velocityY
        } else if (slip > 0f) {
            // 얼음 위에서는 입력이 끊겨도 관성으로 미끄러진다.
            val retained = slip.pow(deltaSeconds)
            tank.slideX *= retained
            tank.slideY *= retained
            velocityX = tank.slideX
            velocityY = tank.slideY
            if (abs(velocityX) < SLIDE_EPSILON) velocityX = 0f
            if (abs(velocityY) < SLIDE_EPSILON) velocityY = 0f
        } else {
            tank.slideX = 0f
            tank.slideY = 0f
            return
        }

        if (velocityX == 0f && velocityY == 0f) return
        moveAxis(tank, velocityX * deltaSeconds, velocityY * deltaSeconds)
    }

    private fun moveAxis(tank: Tank, deltaX: Float, deltaY: Float) {
        val targetX = clampX(tank.x + deltaX)
        val targetY = clampY(tank.y + deltaY)

        if (!blocked(tank, targetX, targetY)) {
            tank.x = targetX
            tank.y = targetY
            return
        }

        // 막혔으면 장애물 표면에 정확히 붙인다. 남은 관성은 버린다.
        val snappedX = if (deltaX > 0f) {
            floor((targetX + Tank.SIZE) / Constants.CELL_PX) * Constants.CELL_PX - Tank.SIZE
        } else if (deltaX < 0f) {
            kotlin.math.ceil(targetX / Constants.CELL_PX) * Constants.CELL_PX
        } else {
            tank.x
        }
        val snappedY = if (deltaY > 0f) {
            floor((targetY + Tank.SIZE) / Constants.CELL_PX) * Constants.CELL_PX - Tank.SIZE
        } else if (deltaY < 0f) {
            kotlin.math.ceil(targetY / Constants.CELL_PX) * Constants.CELL_PX
        } else {
            tank.y
        }

        val candidateX = clampX(if (deltaX != 0f) snappedX else tank.x)
        val candidateY = clampY(if (deltaY != 0f) snappedY else tank.y)
        if (!blocked(tank, candidateX, candidateY)) {
            tank.x = candidateX
            tank.y = candidateY
        }
        tank.slideX = 0f
        tank.slideY = 0f
    }

    private fun blocked(tank: Tank, x: Float, y: Float): Boolean {
        if (map.blocksTank(x, y, Tank.SIZE, Tank.SIZE)) return true
        for (other in tanks) {
            if (other === tank || !other.alive) continue
            if (x < other.x + Tank.SIZE && x + Tank.SIZE > other.x &&
                y < other.y + Tank.SIZE && y + Tank.SIZE > other.y
            ) {
                return true
            }
        }
        return false
    }

    private fun clampX(value: Float): Float = min(max(value, 0f), map.widthPx - Tank.SIZE)

    private fun clampY(value: Float): Float = min(max(value, 0f), map.heightPx - Tank.SIZE)

    private fun snapToCell(value: Float): Float =
        round(value / Constants.CELL_PX) * Constants.CELL_PX

    // -----------------------------------------------------------------------
    // 특수기 (계획서 §6)
    // -----------------------------------------------------------------------

    /**
     * 특수기를 쓴다.
     *
     * 타입별로 하는 일이 다르지만 쿨타임 규칙은 하나다.
     *   - 공격형 **관통탄** : 강철을 부수고 탱크를 관통하는 포탄 한 발 (즉발)
     *   - 방어형 **방어막** : 지속 시간 동안 받는 피해 감소 (지속)
     *   - 스피드형 **대시** : 지속 시간 동안 이동속도 배수 (지속)
     *
     * 즉발형은 발사 쿨타임과 무관하게 나간다. 특수기 쿨타임이 따로 있기 때문이다.
     *
     * @return 실제로 발동했으면 true
     */
    fun activateSpecial(tank: Tank): Boolean {
        if (!tank.canUseSpecial) return false

        when (tank.special) {
            BalanceConfig.Special.PIERCING -> {
                val projectile = launchProjectile(tank, tank.attackPower, piercing = true)
                    ?: return false
                // 관통탄은 발사 쿨타임을 소모하지 않는다. 특수기 쿨타임이 대신 잡아 준다.
                projectile.piercing = true
            }

            BalanceConfig.Special.SHIELD -> {
                tank.specialActiveRemaining = tank.specialDuration
                tank.damageReduction = tank.shieldDamageReduction
            }

            BalanceConfig.Special.DASH -> {
                tank.specialActiveRemaining = tank.specialDuration
            }

            BalanceConfig.Special.NONE -> return false
        }

        tank.specialCooldownRemaining = tank.specialCooldown
        listener?.onSpecialActivated(tank, tank.special)
        return true
    }

    private fun updateSpecial(tank: Tank, deltaSeconds: Float) {
        if (tank.specialCooldownRemaining > 0f) tank.specialCooldownRemaining -= deltaSeconds
        if (tank.specialActiveRemaining <= 0f) return

        tank.specialActiveRemaining -= deltaSeconds
        if (tank.specialActiveRemaining > 0f) return

        // 지속 효과를 되돌린다. 대시는 effectiveMoveSpeed 가 알아서 원래대로 돌아간다.
        tank.specialActiveRemaining = 0f
        if (tank.special == BalanceConfig.Special.SHIELD) tank.damageReduction = 0f
        listener?.onSpecialExpired(tank, tank.special)
    }

    // -----------------------------------------------------------------------
    // 발사와 포탄
    // -----------------------------------------------------------------------

    /**
     * @param power 생략하면 탱크의 공격력을 그대로 쓴다.
     */
    fun fire(tank: Tank, power: Int = tank.attackPower, piercing: Boolean = false): Projectile? {
        if (!tank.canFire) return null
        val projectile = launchProjectile(tank, power, piercing) ?: return null
        tank.fireCooldownRemaining = tank.fireCooldown
        muzzleFlash(tank)
        listener?.onFired(tank)
        return projectile
    }

    /** 쿨타임을 건드리지 않고 포탄만 내보낸다. 일반 발사와 특수기가 함께 쓴다. */
    private fun launchProjectile(tank: Tank, power: Int, piercing: Boolean): Projectile? {
        val projectile = projectiles.obtain() ?: return null

        // 포신 끝에서 나가도록 탱크 중심에서 반 블록 밀어낸다.
        val spawnX = tank.centerX + tank.direction.dx * (Tank.SIZE * 0.5f) - Projectile.SIZE * 0.5f
        val spawnY = tank.centerY + tank.direction.dy * (Tank.SIZE * 0.5f) - Projectile.SIZE * 0.5f
        projectile.launch(
            fromX = spawnX,
            fromY = spawnY,
            direction = tank.direction,
            owner = tank,
            speed = if (piercing) balance.units.piercingProjectileSpeed
            else balance.units.projectileSpeed,
            power = power,
            piercing = piercing,
        )
        return projectile
    }

    /** 총구 화염. 포신 끝에서 진행 방향으로 뻗는다. */
    private fun muzzleFlash(tank: Tank) {
        val offset = Tank.SIZE * 0.55f
        spawnExplosion(
            kind = Explosion.Kind.MUZZLE,
            centerX = tank.centerX + tank.direction.dx * offset,
            centerY = tank.centerY + tank.direction.dy * offset,
            size = Constants.CELL_PX * 1.5f,
            direction = tank.direction,
        )
    }

    private fun updateProjectiles(deltaSeconds: Float) {
        for (projectile in projectiles.active) {
            if (!projectile.active) continue

            // 셀보다 큰 폭으로 전진하면 벽을 뚫고 지나간다. 잘게 나눠 전진한다.
            val distance = projectile.speed * deltaSeconds
            val steps = max(1, kotlin.math.ceil(distance / MAX_SUBSTEP_PX).toInt())
            val stepDistance = distance / steps

            var step = 0
            while (step < steps && projectile.active) {
                projectile.x += projectile.direction.dx * stepDistance
                projectile.y += projectile.direction.dy * stepDistance
                resolveProjectile(projectile)
                step++
            }
        }

        resolveProjectileCollisions()
        projectiles.releaseIf { !it.active }
    }

    private fun resolveProjectile(projectile: Projectile) {
        val centerX = projectile.centerX
        val centerY = projectile.centerY

        if (centerX < 0f || centerY < 0f || centerX >= map.widthPx || centerY >= map.heightPx) {
            explodeProjectile(projectile, Explosion.Kind.BULLET_HIT)
            return
        }

        // 탱크 충돌이 타일보다 먼저다. 벽에 붙어 있는 탱크를 맞힐 수 있어야 한다.
        for (tank in tanks) {
            if (!tank.alive) continue
            if (tank.id == projectile.ownerId) continue
            if (tank.spawnGuardRemaining > 0f) continue
            if (!projectile.overlaps(tank)) continue

            applyDamage(tank, projectile.power, projectile.ownerId)
            if (!projectile.piercing) {
                explodeProjectile(projectile, Explosion.Kind.BULLET_HIT)
                return
            }
        }

        // 포탄은 탱크 중앙에서 나가므로 셀 경계에 정확히 걸친다.
        // 중심 셀 하나만 보면 옆 셀에 있는 벽을 지나쳐 버린다. AABB 전체를 본다.
        val minCellX = map.toCellX(projectile.x)
        val maxCellX = map.toCellX(projectile.x + Projectile.SIZE - CELL_EPSILON)
        val minCellY = map.toCellY(projectile.y)
        val maxCellY = map.toCellY(projectile.y + Projectile.SIZE - CELL_EPSILON)

        var cellX = -1
        var cellY = -1
        outer@ for (cy in minCellY..maxCellY) {
            for (cx in minCellX..maxCellX) {
                if (map.typeAt(cx, cy).blocksBullet) {
                    cellX = cx
                    cellY = cy
                    break@outer
                }
            }
        }
        if (cellX < 0) return

        // 원작처럼 탱크 폭만큼(셀 2칸) 뚫린다.
        val impacts = impactCells(projectile, cellX, cellY)
        var stopped = false
        var brokeSomething = false
        var steelHit = false

        for ((ix, iy) in impacts) {
            val result = map.damageCell(ix, iy, projectile.piercing)
            when (result) {
                TileMap.DamageResult.DESTROYED -> {
                    brokeSomething = true
                    stopped = true
                    listener?.onBrickDestroyed(ix, iy)
                }

                TileMap.DamageResult.EXPLODED -> {
                    brokeSomething = true
                    stopped = true
                    listener?.onBrickDestroyed(ix, iy)
                    detonate(ix, iy)
                }

                TileMap.DamageResult.BASE_HIT -> {
                    stopped = true
                    spawnExplosion(
                        Explosion.Kind.TANK,
                        (ix + 0.5f) * Constants.CELL_PX,
                        (iy + 0.5f) * Constants.CELL_PX,
                        Constants.BLOCK_PX * 1.5f,
                    )
                }

                TileMap.DamageResult.BLOCKED -> {
                    stopped = true
                    if (map.typeAt(ix, iy) == TileType.STEEL) {
                        steelHit = true
                    }
                }

                TileMap.DamageResult.NONE -> Unit
            }
            if (result == TileMap.DamageResult.BASE_HIT) break
        }

        if (stopped) {
            // 무엇에 막혔는지가 눈에 보여야 한다. 강철에 튕긴 것과 벽돌을 부순 것은
            // 다음에 어디를 노려야 하는지가 달라진다.
            val kind = when {
                brokeSomething -> Explosion.Kind.BRICK_BREAK
                steelHit -> Explosion.Kind.STEEL_HIT
                else -> Explosion.Kind.BULLET_HIT
            }
            if (steelHit) listener?.onSteelHit(projectile.centerX, projectile.centerY)
            explodeProjectile(projectile, kind)
        }
    }

    /** 진행 방향에 수직으로 탱크 폭만큼 걸치는 셀들. */
    private fun impactCells(projectile: Projectile, cellX: Int, cellY: Int): List<Pair<Int, Int>> {
        val half = Tank.SIZE * 0.5f
        return if (projectile.direction.isHorizontal) {
            val top = map.toCellY(projectile.centerY - half + 1f)
            val bottom = map.toCellY(projectile.centerY + half - 1f)
            (top..bottom).map { cellX to it }
        } else {
            val left = map.toCellX(projectile.centerX - half + 1f)
            val right = map.toCellX(projectile.centerX + half - 1f)
            (left..right).map { it to cellY }
        }
    }

    /** 포탄끼리 부딪히면 둘 다 사라진다. 원작과 같은 규칙이다. */
    private fun resolveProjectileCollisions() {
        val active = projectiles.active
        for (i in active.indices) {
            val a = active[i]
            if (!a.active) continue
            for (j in i + 1 until active.size) {
                val b = active[j]
                if (!b.active) continue
                if (a.ownerFaction == b.ownerFaction) continue
                if (!a.overlaps(b)) continue
                explodeProjectile(a, Explosion.Kind.BULLET_HIT)
                explodeProjectile(b, Explosion.Kind.BULLET_HIT)
                break
            }
        }
    }

    private fun explodeProjectile(projectile: Projectile, kind: Explosion.Kind) {
        projectile.active = false
        if (kind != Explosion.Kind.STEEL_HIT) {
            listener?.onProjectileHit(projectile.centerX, projectile.centerY)
        }
        spawnExplosion(kind, projectile.centerX, projectile.centerY, Constants.CELL_PX * 1.5f)
    }

    // -----------------------------------------------------------------------
    // 파괴와 폭발
    // -----------------------------------------------------------------------

    /**
     * 피해를 준다. (계획서 §13)
     *
     * ```
     * Damage = max(1, 공격력 - 방어력 x 계수)
     * ```
     * 공식과 계수는 balance.json 이 정한다. 여기서는 적용만 한다.
     *
     * @return 실제로 깎인 HP
     */
    fun applyDamage(tank: Tank, attackPower: Int, attackerId: Int): Int {
        if (!tank.alive || tank.spawnGuardRemaining > 0f) return 0

        var amount = balance.damageOf(attackPower, tank.defensePower)
        // 방어형 특수기(방어막)는 Phase 4 에서 켜진다. 여기서는 훅만 열어 둔다.
        amount = (amount * (1f - tank.damageReduction)).toInt().coerceAtLeast(1)

        val applied = kotlin.math.min(amount, tank.hp)
        tank.hp -= applied

        if (tank.hp <= 0) {
            destroyTank(tank, attackerId)
        } else {
            listener?.onTankDamaged(tank, applied, attackerId)
        }
        return applied
    }

    fun destroyTank(tank: Tank, killerId: Int) {
        if (!tank.alive) return
        tank.alive = false
        spawnExplosion(Explosion.Kind.TANK, tank.centerX, tank.centerY, Tank.SIZE * 1.6f)
        listener?.onTankDestroyed(tank, killerId)
    }

    /** 폭발성 프롭의 연쇄 반응. 무한 재귀를 막기 위해 큐로 처리한다. */
    private fun detonate(cellX: Int, cellY: Int) {
        val queue = ArrayDeque<Int>()
        queue += cellY * map.cellsX + cellX
        var guard = 0

        while (queue.isNotEmpty() && guard < MAX_CHAIN) {
            guard++
            val index = queue.removeFirst()
            val cx = index % map.cellsX
            val cy = index / map.cellsX

            spawnExplosion(
                Explosion.Kind.TANK,
                (cx + 0.5f) * Constants.CELL_PX,
                (cy + 0.5f) * Constants.CELL_PX,
                Constants.BLOCK_PX * 1.2f,
            )

            for (chained in map.explode(cx, cy, config.defaultBlastCells)) {
                queue += chained
            }

            // 폭발 반경 안의 탱크도 휘말린다.
            val radiusPx = config.defaultBlastCells * Constants.CELL_PX
            val blastX = (cx + 0.5f) * Constants.CELL_PX
            val blastY = (cy + 0.5f) * Constants.CELL_PX
            for (tank in tanks) {
                if (!tank.alive || tank.spawnGuardRemaining > 0f) continue
                if (abs(tank.centerX - blastX) <= radiusPx && abs(tank.centerY - blastY) <= radiusPx) {
                    applyDamage(tank, BLAST_ATTACK_POWER, -1)
                }
            }
        }
    }

    fun spawnExplosion(
        kind: Explosion.Kind,
        centerX: Float,
        centerY: Float,
        size: Float,
        direction: Direction = Direction.UP,
    ) {
        val explosion = explosions.obtain() ?: return
        val frames = when (kind) {
            Explosion.Kind.TANK -> 8
            Explosion.Kind.SPAWN -> 4
            else -> 1
        }
        val fps = when (kind) {
            Explosion.Kind.TANK -> 20f
            Explosion.Kind.SPAWN -> 8f
            Explosion.Kind.MUZZLE -> 16f
            else -> 12f
        }
        explosion.start(kind, centerX, centerY, size, frames, fps, direction)
    }

    // -----------------------------------------------------------------------

    /** 본진에 인접한 걸을 수 있는 셀. AI 와 검증이 함께 쓴다. */
    fun baseCellCenter(): Pair<Float, Float> {
        val (px, py) = stage.blockToPx(stage.baseBlock)
        return px + Constants.BLOCK_PX * 0.5f to py + Constants.BLOCK_PX * 0.5f
    }

    fun tileAt(cellX: Int, cellY: Int): TileType = map.typeAt(cellX, cellY)

    private companion object {
        /** 포탄이 한 번에 전진할 수 있는 최대 거리. 셀(32px)의 1/4. */
        const val MAX_SUBSTEP_PX = 8f
        const val CELL_EPSILON = 0.001f
        const val SLIDE_EPSILON = 1f
        const val MAX_CHAIN = 64

        /** 폭발성 프롭의 공격력. 방어형도 두 방이면 터지도록 잡았다. */
        const val BLAST_ATTACK_POWER = 4
    }
}

package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction

/**
 * 탱크의 데이터 모델. (계획서 §41-5)
 *
 * 렌더링 정보를 담지 않는다. 어떤 스프라이트를 쓸지는 [colorSlot] / [type] 을 보고
 * 렌더러가 매니페스트에서 찾는다.
 */
class Tank(val id: Int) {

    enum class Faction { PLAYER, ENEMY }

    enum class Type { ATTACK, DEFENSE, SPEED }

    var faction: Faction = Faction.PLAYER
    var type: Type = Type.ATTACK

    /** 플레이어 색상 슬롯 0..3. 적은 -1. */
    var colorSlot: Int = 0

    /** 이 탱크를 조종하는 플레이어 슬롯. COM 은 -1. */
    var ownerSlot: Int = -1

    // --- 능력치 (계획서 §7) -------------------------------------------------
    // 값은 balance.json 에서 오고 여기서는 담기만 한다. 하드코딩하지 않는다.

    var attackPower: Int = 1

    var defensePower: Int = 1

    /** HP 와 Life 는 분리한다. HP 0 이면 탱크가 부서지고 Life 가 1 줄어든다. (계획서 §12) */
    var maxHp: Int = 100

    var hp: Int = 100

    /** 좌상단 기준 논리 픽셀 좌표. */
    var x: Float = 0f
    var y: Float = 0f

    var direction: Direction = Direction.UP

    /** 이번 틱에 이동 입력이 있는지. */
    var moving: Boolean = false

    /** 논리 px/초. */
    var moveSpeed: Float = Constants.BLOCK_PX * 2.5f

    var fireCooldown: Float = 0.45f
    var fireCooldownRemaining: Float = 0f

    var alive: Boolean = false

    /** 얼음 위에서 미끄러지는 잔여 속도. */
    var slideX: Float = 0f
    var slideY: Float = 0f

    // --- 특수기 (계획서 §6) -------------------------------------------------
    // 능력치와 마찬가지로 값은 balance.json 에서 오고 여기서는 상태만 든다.

    var special: BalanceConfig.Special = BalanceConfig.Special.NONE

    /** 재사용까지 남은 시간. 0 이하면 쓸 수 있다. */
    var specialCooldownRemaining: Float = 0f

    /** 지속형 특수기가 남긴 시간. 0 이하면 효과가 꺼져 있다. */
    var specialActiveRemaining: Float = 0f

    var specialCooldown: Float = 0f

    var specialDuration: Float = 0f

    /** 방어막이 켜졌을 때 적용할 피해 감소율. */
    var shieldDamageReduction: Float = 0f

    /** 대시가 켜졌을 때 곱할 이동속도 배수. */
    var dashSpeedMultiplier: Float = 1f

    /**
     * 받는 피해 감소율 0..1. 방어막이 켜져 있는 동안만 0보다 크다. (계획서 §6.2)
     */
    var damageReduction: Float = 0f

    /** 스폰 직후 무적 시간. 스폰 킬을 막는다. */
    var spawnGuardRemaining: Float = 0f

    val centerX: Float get() = x + SIZE * 0.5f
    val centerY: Float get() = y + SIZE * 0.5f

    val canFire: Boolean get() = alive && fireCooldownRemaining <= 0f

    val canUseSpecial: Boolean
        get() = alive && special != BalanceConfig.Special.NONE && specialCooldownRemaining <= 0f

    val specialActive: Boolean get() = specialActiveRemaining > 0f

    /** 0..1. 1이면 바로 쓸 수 있다. HUD 게이지에 그대로 쓴다. */
    val specialReadyRatio: Float
        get() = if (specialCooldown <= 0f) 1f
        else (1f - specialCooldownRemaining / specialCooldown).coerceIn(0f, 1f)

    /** 대시 중에는 더 빨리 달린다. */
    val effectiveMoveSpeed: Float
        get() = if (specialActive && special == BalanceConfig.Special.DASH) {
            moveSpeed * dashSpeedMultiplier
        } else {
            moveSpeed
        }

    /** 0..1 로 정규화한 체력. HUD 게이지용. */
    val hpRatio: Float get() = if (maxHp <= 0) 0f else (hp.toFloat() / maxHp).coerceIn(0f, 1f)

    fun spawnAt(px: Float, py: Float, direction: Direction) {
        x = px
        y = py
        this.direction = direction
        moving = false
        alive = true
        hp = maxHp
        fireCooldownRemaining = 0f
        slideX = 0f
        slideY = 0f
        damageReduction = 0f
        specialCooldownRemaining = 0f
        specialActiveRemaining = 0f
        spawnGuardRemaining = SPAWN_GUARD_SECONDS
    }

    fun overlaps(other: Tank): Boolean =
        x < other.x + SIZE && x + SIZE > other.x &&
            y < other.y + SIZE && y + SIZE > other.y

    companion object {
        /** 원작과 같이 탱크는 정확히 한 블록(2x2 셀)을 차지한다. */
        const val SIZE: Float = Constants.BLOCK_PX

        const val SPAWN_GUARD_SECONDS: Float = 1.5f
    }
}

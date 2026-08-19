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

    /** 스폰 직후 무적 시간. 스폰 킬을 막는다. */
    var spawnGuardRemaining: Float = 0f

    val centerX: Float get() = x + SIZE * 0.5f
    val centerY: Float get() = y + SIZE * 0.5f

    val canFire: Boolean get() = alive && fireCooldownRemaining <= 0f

    fun spawnAt(px: Float, py: Float, direction: Direction) {
        x = px
        y = py
        this.direction = direction
        moving = false
        alive = true
        fireCooldownRemaining = 0f
        slideX = 0f
        slideY = 0f
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

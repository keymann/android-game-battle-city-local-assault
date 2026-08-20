package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction

/** 포탄. 풀에서 재사용되므로 생성자에서 상태를 잡지 않는다. (계획서 §41-10) */
class Projectile(val id: Int) {

    var x: Float = 0f
    var y: Float = 0f
    var direction: Direction = Direction.UP
    var speed: Float = Constants.BLOCK_PX * 7f

    /** 발사한 탱크 id. 자기 포탄에 맞지 않도록 한다. */
    var ownerId: Int = -1
    var ownerFaction: Tank.Faction = Tank.Faction.PLAYER

    var power: Int = 1

    /** 관통탄이면 강철도 부수고 탱크를 관통한다. (계획서 §6.1) */
    var piercing: Boolean = false

    var active: Boolean = false

    val centerX: Float get() = x + SIZE * 0.5f
    val centerY: Float get() = y + SIZE * 0.5f

    fun launch(
        fromX: Float,
        fromY: Float,
        direction: Direction,
        owner: Tank,
        speed: Float,
        power: Int,
        piercing: Boolean,
    ) {
        this.x = fromX
        this.y = fromY
        this.direction = direction
        this.ownerId = owner.id
        this.ownerFaction = owner.faction
        this.speed = speed
        this.power = power
        this.piercing = piercing
        active = true
    }

    fun overlaps(tank: Tank): Boolean =
        x < tank.x + Tank.SIZE && x + SIZE > tank.x &&
            y < tank.y + Tank.SIZE && y + SIZE > tank.y

    fun overlaps(other: Projectile): Boolean =
        x < other.x + SIZE && x + SIZE > other.x &&
            y < other.y + SIZE && y + SIZE > other.y

    companion object {
        /** 원작의 포탄은 타일보다 훨씬 작다. */
        const val SIZE: Float = Constants.CELL_PX * 0.25f
    }
}

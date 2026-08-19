package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Direction

/** 폭발 이펙트. 프레임 목록은 매니페스트가 정하므로 여기서는 종류만 든다. */
class Explosion(val id: Int) {

    enum class Kind {
        TANK,
        BULLET_HIT,
        BRICK_BREAK,

        /** 강철에 튕겼다. 부수지 못했다는 것이 눈에 보여야 한다. */
        STEEL_HIT,

        /** 포구 화염. 방향이 있는 유일한 이펙트다. */
        MUZZLE,

        SPAWN,
    }

    var kind: Kind = Kind.BULLET_HIT
    var x: Float = 0f
    var y: Float = 0f
    var size: Float = 0f
    var elapsed: Float = 0f
    var frameCount: Int = 1
    var fps: Float = 20f
    var active: Boolean = false

    /** 방향이 있는 이펙트만 쓴다. 포구 화염은 총구가 향한 쪽으로 뻗어야 한다. */
    var direction: Direction = Direction.UP

    val frameIndex: Int
        get() = (elapsed * fps).toInt().coerceIn(0, frameCount - 1)

    val finished: Boolean get() = elapsed >= frameCount / fps

    fun start(
        kind: Kind,
        centerX: Float,
        centerY: Float,
        size: Float,
        frameCount: Int,
        fps: Float,
        direction: Direction = Direction.UP,
    ) {
        this.kind = kind
        this.direction = direction
        this.size = size
        this.x = centerX - size * 0.5f
        this.y = centerY - size * 0.5f
        this.frameCount = frameCount.coerceAtLeast(1)
        this.fps = fps
        elapsed = 0f
        active = true
    }

    fun update(deltaSeconds: Float) {
        elapsed += deltaSeconds
    }
}

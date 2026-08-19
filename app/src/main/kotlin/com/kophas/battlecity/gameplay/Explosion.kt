package com.kophas.battlecity.gameplay

/** 폭발 이펙트. 프레임 목록은 매니페스트가 정하므로 여기서는 종류만 든다. */
class Explosion(val id: Int) {

    enum class Kind { TANK, BULLET_HIT, BRICK_BREAK, SPAWN }

    var kind: Kind = Kind.BULLET_HIT
    var x: Float = 0f
    var y: Float = 0f
    var size: Float = 0f
    var elapsed: Float = 0f
    var frameCount: Int = 1
    var fps: Float = 20f
    var active: Boolean = false

    val frameIndex: Int
        get() = (elapsed * fps).toInt().coerceIn(0, frameCount - 1)

    val finished: Boolean get() = elapsed >= frameCount / fps

    fun start(kind: Kind, centerX: Float, centerY: Float, size: Float, frameCount: Int, fps: Float) {
        this.kind = kind
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

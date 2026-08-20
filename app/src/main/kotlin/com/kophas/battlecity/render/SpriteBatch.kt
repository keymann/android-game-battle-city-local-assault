package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * 프레임 단위 스프라이트 수집기. (계획서 §25.1, §39 Draw Call 최소화)
 *
 * 게임 코드가 [draw] 로 쌓아 두면 [end] 에서 `(layer, textureId)` 로 정렬하여
 * 같은 텍스처가 연속되도록 재배치하고, 텍스처가 바뀌는 지점마다 run 을 만든다.
 * 네이티브는 run 단위로만 디스크립터/텍스처를 다시 바인딩한다.
 *
 * 프레임마다 JNI 배열 복사가 일어나지 않도록 direct ByteBuffer 를 재사용한다.
 * 정렬은 인덱스를 담은 `LongArray` 하나만 쓰므로 프레임당 할당이 없다.
 */
class SpriteBatch(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity in 1..MAX_CAPACITY) { "capacity 는 1..$MAX_CAPACITY 범위여야 한다" }
    }

    /** 스프라이트 1개당 float 개수. 네이티브 `common/sprite.h` 와 반드시 같아야 한다. */
    private val staging = FloatArray(capacity * FLOATS_PER_SPRITE)

    /**
     * 정렬 키.
     * `[layer:16][textureId:16][삽입순서:32]`
     * 삽입 순서를 최하위에 두어 같은 레이어/텍스처 안에서는 안정 정렬이 된다.
     */
    private val keys = LongArray(capacity)

    private val spriteByteBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(capacity * FLOATS_PER_SPRITE * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

    private val spriteFloats: FloatBuffer = spriteByteBuffer.asFloatBuffer()

    private val runByteBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(capacity * 2 * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())

    private val runInts: IntBuffer = runByteBuffer.asIntBuffer()

    private var pending = 0
    private var overflowed = 0

    /** 네이티브에 넘길 스프라이트 개수. [end] 이후에 유효하다. */
    var spriteCount: Int = 0
        private set

    /** `[textureId, count]` 쌍의 개수. */
    var runCount: Int = 0
        private set

    /** 상한을 넘겨 버려진 스프라이트 수. 0이 아니면 capacity 를 늘려야 한다. */
    var droppedSprites: Int = 0
        private set

    val spriteBuffer: ByteBuffer get() = spriteByteBuffer
    val runBuffer: ByteBuffer get() = runByteBuffer

    fun begin() {
        pending = 0
        overflowed = 0
        spriteCount = 0
        runCount = 0
    }

    /**
     * @param x,y 논리 월드 좌표가 아니라 **화면 픽셀 좌표**(좌상단 원점)다.
     *            월드 -> 화면 변환은 [Viewport] 가 담당한다.
     * @param originX,originY 회전 중심. 0..1 의 정규화 좌표(0.5,0.5 가 중앙).
     * @param rotation 라디안.
     * @param layer [Constants.Layer] 값. 작을수록 먼저 그린다.
     */
    @Suppress("LongParameterList")
    fun draw(
        region: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        layer: Int = Constants.Layer.ENTITY,
        rotation: Float = 0f,
        originX: Float = 0.5f,
        originY: Float = 0.5f,
        red: Float = 1f,
        green: Float = 1f,
        blue: Float = 1f,
        alpha: Float = 1f,
    ) {
        if (pending >= capacity) {
            overflowed++
            return
        }
        val slot = pending
        val base = slot * FLOATS_PER_SPRITE
        staging[base + 0] = x
        staging[base + 1] = y
        staging[base + 2] = width
        staging[base + 3] = height
        staging[base + 4] = region.u0
        staging[base + 5] = region.v0
        staging[base + 6] = region.u1
        staging[base + 7] = region.v1
        staging[base + 8] = originX
        staging[base + 9] = originY
        staging[base + 10] = rotation
        staging[base + 11] = 0f
        staging[base + 12] = red
        staging[base + 13] = green
        staging[base + 14] = blue
        staging[base + 15] = alpha

        val clampedLayer = layer.coerceIn(0, MAX_LAYER)
        keys[slot] = (clampedLayer.toLong() shl 48) or
            ((region.textureId.toLong() and 0xFFFF) shl 32) or
            slot.toLong()
        pending++
    }

    /** 정렬하고 direct 버퍼를 채운다. 호출 후 [spriteBuffer] / [runBuffer] 가 유효해진다. */
    fun end() {
        droppedSprites = overflowed
        spriteCount = pending
        runCount = 0
        if (pending == 0) {
            spriteFloats.clear()
            runInts.clear()
            return
        }

        java.util.Arrays.sort(keys, 0, pending)

        spriteFloats.clear()
        runInts.clear()

        var currentTexture = -1
        var currentCount = 0
        for (i in 0 until pending) {
            val key = keys[i]
            val slot = (key and 0xFFFFFFFFL).toInt()
            val textureId = ((key ushr 32) and 0xFFFF).toInt()

            spriteFloats.put(staging, slot * FLOATS_PER_SPRITE, FLOATS_PER_SPRITE)

            if (textureId != currentTexture) {
                if (currentCount > 0) {
                    runInts.put(currentTexture)
                    runInts.put(currentCount)
                    runCount++
                }
                currentTexture = textureId
                currentCount = 0
            }
            currentCount++
        }
        if (currentCount > 0) {
            runInts.put(currentTexture)
            runInts.put(currentCount)
            runCount++
        }

        spriteFloats.flip()
        runInts.flip()
    }

    /** 테스트용. [end] 이후 i번째 스프라이트의 f번째 float 을 읽는다. */
    internal fun spriteFloatAt(index: Int, field: Int): Float =
        spriteFloats.get(index * FLOATS_PER_SPRITE + field)

    /** 테스트용. i번째 run 의 `[textureId, count]`. */
    internal fun runAt(index: Int): Pair<Int, Int> =
        runInts.get(index * 2) to runInts.get(index * 2 + 1)

    companion object {
        const val FLOATS_PER_SPRITE: Int = 16
        const val DEFAULT_CAPACITY: Int = 8192

        /** 네이티브 인덱스 버퍼가 uint16 이라 쿼드 상한이 8192(정점 32768)다. */
        const val MAX_CAPACITY: Int = 8192

        private const val MAX_LAYER: Int = 0x7FFF
    }
}

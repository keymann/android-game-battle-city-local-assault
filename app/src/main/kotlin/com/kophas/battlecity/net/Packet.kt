package com.kophas.battlecity.net

/**
 * 바이트 버퍼에 값을 채우고 읽는 최소 도구.
 *
 * `java.nio.ByteBuffer` 를 쓰지 않는다. 패킷마다 버퍼를 새로 잡으면 초당 스무 번씩
 * 쓰레기가 쌓이고, 그 정리 비용이 그대로 프레임 끊김으로 나타난다. 세션이 버퍼
 * 하나를 들고 재사용할 수 있게 커서만 옮기는 형태로 둔다.
 *
 * 바이트 순서는 **리틀 엔디언**으로 고정한다. 기기마다 다르면 같은 패킷을 서로
 * 다르게 읽는다.
 */
class PacketWriter(val buffer: ByteArray) {

    var length: Int = 0
        private set

    fun reset(): PacketWriter {
        length = 0
        return this
    }

    fun byte(value: Int): PacketWriter {
        buffer[length++] = value.toByte()
        return this
    }

    fun bool(value: Boolean): PacketWriter = byte(if (value) 1 else 0)

    fun short(value: Int): PacketWriter {
        buffer[length++] = (value and 0xFF).toByte()
        buffer[length++] = ((value ushr 8) and 0xFF).toByte()
        return this
    }

    fun int(value: Int): PacketWriter {
        for (shift in 0 until 32 step 8) {
            buffer[length++] = ((value ushr shift) and 0xFF).toByte()
        }
        return this
    }

    fun long(value: Long): PacketWriter {
        for (shift in 0 until 64 step 8) {
            buffer[length++] = ((value ushr shift) and 0xFF).toByte()
        }
        return this
    }

    /**
     * 논리 좌표. 0.25px 단위로 접어 2바이트에 담는다.
     *
     * 맵이 가장 클 때 2048px 이므로 8192 단계면 넉넉하다. 4바이트 실수를 그대로
     * 보내면 스냅샷 하나가 두 배로 불어난다.
     */
    fun position(value: Float): PacketWriter =
        short((value * Protocol.POSITION_SCALE).toInt().coerceIn(0, 0xFFFF))

    /** 짧은 문자열. 길이 1바이트 + UTF-8 본문. */
    fun text(value: String, limit: Int = Protocol.MAX_NAME): PacketWriter {
        val bytes = value.encodeToByteArray()
        val size = minOf(bytes.size, limit)
        byte(size)
        bytes.copyInto(buffer, length, 0, size)
        length += size
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(length)
}

class PacketReader(private val buffer: ByteArray, private val limit: Int) {

    var position: Int = 0
        private set

    val remaining: Int get() = limit - position

    fun byte(): Int = buffer[position++].toInt() and 0xFF

    fun bool(): Boolean = byte() != 0

    fun short(): Int {
        val low = byte()
        val high = byte()
        return low or (high shl 8)
    }

    fun int(): Int {
        var value = 0
        for (shift in 0 until 32 step 8) value = value or (byte() shl shift)
        return value
    }

    fun long(): Long {
        var value = 0L
        for (shift in 0 until 64 step 8) value = value or (byte().toLong() shl shift)
        return value
    }

    fun position(): Float = short() / Protocol.POSITION_SCALE

    fun text(): String {
        val size = byte()
        val value = buffer.decodeToString(position, position + size)
        position += size
        return value
    }
}

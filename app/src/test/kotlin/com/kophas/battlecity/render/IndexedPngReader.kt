package com.kophas.battlecity.render

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 테스트 전용 최소 PNG 디코더 (8bit, 비인터레이스).
 *
 * 안드로이드 단위 테스트는 `android.jar` 를 컴파일 클래스패스로 쓰기 때문에
 * `javax.imageio` 가 없다. 아틀라스 픽셀을 실제로 검사하려면 직접 읽어야 한다.
 * `java.util.zip.Inflater` 는 android.jar 에 있으므로 추가 의존성은 없다.
 *
 * 지원 범위는 이 프로젝트의 아틀라스가 쓰는 형식으로 한정한다.
 *   colorType 2 (RGB) / 3 (팔레트) / 6 (RGBA)
 * 다른 형식이 들어오면 조용히 넘어가지 않고 예외로 알린다.
 */
class IndexedPngReader private constructor(
    val width: Int,
    val height: Int,
    private val pixels: IntArray,
) {
    /** ARGB 8888. */
    fun argbAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) 가 범위를 벗어났다" }
        return pixels[y * width + x]
    }

    companion object {

        fun read(file: java.io.File): IndexedPngReader {
            val bytes = file.readBytes()
            require(bytes.size > 8) { "PNG 가 너무 짧다: ${file.name}" }
            require(
                bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                    bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte(),
            ) { "PNG 시그니처가 아니다: ${file.name}" }

            var width = 0
            var height = 0
            var colorType = -1
            var palette: IntArray? = null
            var alphas = IntArray(0)
            val idat = ByteArrayOutputStream()

            var offset = 8
            while (offset + 8 <= bytes.size) {
                val length = readInt(bytes, offset)
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                val dataStart = offset + 8

                when (type) {
                    "IHDR" -> {
                        width = readInt(bytes, dataStart)
                        height = readInt(bytes, dataStart + 4)
                        val bitDepth = bytes[dataStart + 8].toInt()
                        colorType = bytes[dataStart + 9].toInt()
                        val interlace = bytes[dataStart + 12].toInt()
                        require(bitDepth == 8) { "8bit 만 지원한다 (bitDepth=$bitDepth)" }
                        require(colorType in setOf(2, 3, 6)) {
                            "RGB / 팔레트 / RGBA 만 지원한다 (colorType=$colorType)"
                        }
                        require(interlace == 0) { "인터레이스는 지원하지 않는다" }
                    }

                    "PLTE" -> palette = IntArray(length / 3) { i ->
                        val r = bytes[dataStart + i * 3].toInt() and 0xFF
                        val g = bytes[dataStart + i * 3 + 1].toInt() and 0xFF
                        val b = bytes[dataStart + i * 3 + 2].toInt() and 0xFF
                        (r shl 16) or (g shl 8) or b
                    }

                    "tRNS" -> alphas = IntArray(length) { bytes[dataStart + it].toInt() and 0xFF }

                    "IDAT" -> idat.write(bytes, dataStart, length)

                    "IEND" -> offset = bytes.size
                }
                offset = dataStart + length + 4
            }

            val bytesPerPixel = when (colorType) {
                2 -> 3
                3 -> 1
                else -> 4
            }
            val stride = width * bytesPerPixel
            val raw = inflate(idat.toByteArray(), (stride + 1) * height)
            val scanlines = unfilter(raw, stride, height, bytesPerPixel)

            val pixels = IntArray(width * height)
            for (i in 0 until width * height) {
                val at = i * bytesPerPixel
                pixels[i] = when (colorType) {
                    3 -> {
                        val index = scanlines[at]
                        val colors = requireNotNull(palette) { "PLTE 청크가 없다" }
                        val alpha = if (index < alphas.size) alphas[index] else 0xFF
                        (alpha shl 24) or colors[index]
                    }

                    2 -> (0xFF shl 24) or
                        (scanlines[at] shl 16) or (scanlines[at + 1] shl 8) or scanlines[at + 2]

                    else -> (scanlines[at + 3] shl 24) or
                        (scanlines[at] shl 16) or (scanlines[at + 1] shl 8) or scanlines[at + 2]
                }
            }
            return IndexedPngReader(width, height, pixels)
        }

        private fun readInt(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)

        private fun inflate(data: ByteArray, expected: Int): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArray(expected)
            var written = 0
            while (!inflater.finished() && written < expected) {
                val produced = inflater.inflate(out, written, expected - written)
                if (produced == 0) break
                written += produced
            }
            inflater.end()
            check(written == expected) { "압축 해제 결과가 $written 바이트로 기대($expected)와 다르다" }
            return out
        }

        /** 필터를 풀어 스캔라인을 이어 붙인 바이트 배열로 돌려준다. */
        private fun unfilter(raw: ByteArray, stride: Int, height: Int, bpp: Int): IntArray {
            val result = IntArray(stride * height)
            val previous = IntArray(stride)
            val current = IntArray(stride)

            for (y in 0 until height) {
                val rowStart = y * (stride + 1)
                val filter = raw[rowStart].toInt() and 0xFF
                for (x in 0 until stride) {
                    val value = raw[rowStart + 1 + x].toInt() and 0xFF
                    val left = if (x >= bpp) current[x - bpp] else 0
                    val up = previous[x]
                    val upperLeft = if (x >= bpp) previous[x - bpp] else 0

                    val restored = when (filter) {
                        0 -> value
                        1 -> value + left
                        2 -> value + up
                        3 -> value + (left + up) / 2
                        4 -> value + paeth(left, up, upperLeft)
                        else -> error("알 수 없는 PNG 필터 $filter")
                    } and 0xFF

                    current[x] = restored
                    result[y * stride + x] = restored
                }
                current.copyInto(previous)
            }
            return result
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = kotlin.math.abs(p - a)
            val pb = kotlin.math.abs(p - b)
            val pc = kotlin.math.abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
    }
}

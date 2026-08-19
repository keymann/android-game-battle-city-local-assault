package com.keymann.battlecity.util

/**
 * 의존성 없는 최소 JSON 파서.
 *
 * `org.json` 은 안드로이드 프레임워크 구현이라 JVM 단위 테스트에서 스텁으로 동작한다.
 * 매니페스트는 런타임과 테스트가 완전히 동일하게 읽어야 하므로 직접 파싱한다.
 * (계획서 §41-13 기능 구현 후 테스트 추가)
 */
sealed class JsonValue {

    data object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Text(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    operator fun get(index: Int): JsonValue? = (this as? Arr)?.items?.getOrNull(index)

    val asString: String? get() = (this as? Text)?.value
    val asInt: Int? get() = (this as? Num)?.value?.toInt()
    val asFloat: Float? get() = (this as? Num)?.value?.toFloat()
    val asBoolean: Boolean? get() = (this as? Bool)?.value
    val asArray: List<JsonValue> get() = (this as? Arr)?.items ?: emptyList()
    val asObject: Map<String, JsonValue> get() = (this as? Obj)?.fields ?: emptyMap()

    /** 문자열 배열. 스프라이트 후보 목록처럼 자주 쓰는 형태라 헬퍼로 둔다. */
    val asStringList: List<String> get() = asArray.mapNotNull { it.asString }

    /** 정수 배열. tiny 아틀라스 인덱스 목록용. */
    val asIntList: List<Int> get() = asArray.mapNotNull { it.asInt }

    companion object {
        fun parse(source: String): JsonValue = JsonParser(source).parseDocument()
    }
}

class JsonParseException(message: String) : RuntimeException(message)

private class JsonParser(private val src: String) {

    private var pos = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (pos != src.length) fail("문서 끝에 잉여 문자가 있다")
        return value
    }

    private fun parseValue(): JsonValue {
        if (pos >= src.length) fail("값이 오기 전에 입력이 끝났다")
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Text(parseString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("예상치 못한 문자 '$c'")
        }
    }

    private fun parseObject(): JsonValue {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            fields[key] = parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> Unit
                '}' -> return JsonValue.Obj(fields)
                else -> fail("객체에서 ',' 또는 '}' 를 기대했으나 '$c'")
            }
        }
    }

    private fun parseArray(): JsonValue {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonValue.Arr(items)
        }
        while (true) {
            skipWhitespace()
            items += parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> Unit
                ']' -> return JsonValue.Arr(items)
                else -> fail("배열에서 ',' 또는 ']' 를 기대했으나 '$c'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail("문자열이 닫히지 않았다")
            when (val c = src[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) fail("이스케이프가 잘렸다")
                    when (val e = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) fail("\\u 이스케이프가 잘렸다")
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> fail("알 수 없는 이스케이프 '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (peek() == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        if (pos < src.length && src[pos] == '.') {
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val text = src.substring(start, pos)
        return JsonValue.Num(text.toDoubleOrNull() ?: fail("숫자를 해석할 수 없다: $text"))
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!src.startsWith(word, pos)) fail("'$word' 를 기대했다")
        pos += word.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else ' '

    private fun next(): Char {
        if (pos >= src.length) fail("입력이 예기치 않게 끝났다")
        return src[pos++]
    }

    private fun expect(c: Char) {
        if (next() != c) fail("'$c' 를 기대했다")
    }

    private fun fail(message: String): Nothing =
        throw JsonParseException("$message (offset=$pos)")
}

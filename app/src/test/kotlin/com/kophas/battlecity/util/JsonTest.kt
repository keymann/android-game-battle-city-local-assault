package com.kophas.battlecity.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `기본 타입을 읽는다`() {
        assertEquals("hi", JsonValue.parse(""""hi"""").asString)
        assertEquals(42, JsonValue.parse("42").asInt)
        assertEquals(-1.5f, JsonValue.parse("-1.5").asFloat!!, 1e-6f)
        assertEquals(true, JsonValue.parse("true").asBoolean)
        assertTrue(JsonValue.parse("null") is JsonValue.Null)
    }

    @Test
    fun `중첩 객체와 배열을 읽는다`() {
        val json = """{"a":{"b":[1,2,{"c":"d"}]}}"""
        val root = JsonValue.parse(json)
        assertEquals("d", root["a"]?.get("b")?.get(2)?.get("c")?.asString)
        assertEquals(listOf(1, 2), root["a"]?.get("b")?.asArray?.take(2)?.mapNotNull { it.asInt })
    }

    @Test
    fun `빈 객체와 배열을 읽는다`() {
        assertEquals(emptyMap<String, JsonValue>(), JsonValue.parse("{}").asObject)
        assertEquals(emptyList<JsonValue>(), JsonValue.parse("[]").asArray)
    }

    @Test
    fun `이스케이프를 해석한다`() {
        val parsed = JsonValue.parse(""""a\"b\\c\ndA0"""").asString
        assertEquals("a\"b\\c\ndA0", parsed)
    }

    @Test
    fun `지수 표기를 읽는다`() {
        assertEquals(1500.0f, JsonValue.parse("1.5e3").asFloat!!, 1e-3f)
        assertEquals(0.015f, JsonValue.parse("1.5E-2").asFloat!!, 1e-6f)
    }

    @Test
    fun `키 순서를 보존한다`() {
        val root = JsonValue.parse("""{"z":1,"a":2,"m":3}""")
        assertEquals(listOf("z", "a", "m"), root.asObject.keys.toList())
    }

    @Test
    fun `타입이 맞지 않으면 null 을 준다`() {
        val root = JsonValue.parse("""{"n":1}""")
        assertNull(root["n"]?.asString)
        assertNull(root["없는키"])
        assertNull(root[0])
    }

    @Test
    fun `잘못된 JSON 은 예외를 던진다`() {
        listOf("{", "[1,]", """{"a"}""", """{"a":1}extra""", "tru", """ "닫히지않음 """).forEach {
            runCatching { JsonValue.parse(it) }
                .onSuccess { _ -> error("'$it' 는 실패해야 한다") }
                .onFailure { e -> assertTrue(e is JsonParseException) }
        }
    }

    @Test
    fun `공백과 개행을 무시한다`() {
        val root = JsonValue.parse("  {\n  \"a\" :\t [ 1 , 2 ]\n}  ")
        assertEquals(listOf(1, 2), root["a"]?.asIntList)
    }
}

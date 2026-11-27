package io.github.openscreen.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Every shared fixture must be accepted / rejected exactly as the JSON schema does. */
class MessageConformanceTest {

    @Test
    fun `accepts every valid fixture`() {
        val files = SharedFixtures.files("fixtures/valid")
        assertTrue(files.isNotEmpty())
        for (file in files) {
            when (val r = SignalingCodec.decode(file.readText())) {
                is DecodeResult.Ok -> Unit
                is DecodeResult.Invalid -> fail("${file.name} rejected: ${r.reason}")
            }
        }
    }

    @Test
    fun `rejects every invalid fixture`() {
        val files = SharedFixtures.files("fixtures/invalid")
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val r = SignalingCodec.decode(file.readText())
            if (r is DecodeResult.Ok) fail("${file.name} accepted as ${r.message}")
        }
    }

    @Test
    fun `valid fixtures round-trip through encode`() {
        for (file in SharedFixtures.files("fixtures/valid")) {
            val first = (SignalingCodec.decode(file.readText()) as DecodeResult.Ok).message
            val again = (SignalingCodec.decode(SignalingCodec.encode(first)) as DecodeResult.Ok).message
            assertEquals(file.name, first, again)
        }
    }

    @Test
    fun `encode writes the type discriminator`() {
        val text = SignalingCodec.encode(Bye("user"))
        assertEquals("""{"type":"bye","reason":"user"}""", text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode refuses invalid messages`() {
        SignalingCodec.encode(Bye("because"))
    }

    @Test
    fun `error codes match constants json`() {
        val shared = SharedFixtures.json("constants.json").getValue("errorCodes").toString()
        for (code in ERROR_CODES) assertTrue(code, shared.contains("\"$code\""))
    }

    @Test
    fun `oversize messages are rejected before parsing`() {
        val big = """{"type":"bye","reason":"user","pad":"${"x".repeat(70_000)}"}"""
        assertTrue(SignalingCodec.decode(big) is DecodeResult.Invalid)
    }
}

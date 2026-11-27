package io.github.openscreen.core.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs the shared pairing-URI vectors (shared/protocol/fixtures/pairing-uris.json). */
class PairingUriConformanceTest {
    private val vectors = SharedFixtures.json("fixtures/pairing-uris.json")
    private val supported = vectors.getValue("supportedVersion").jsonPrimitive.int

    @Test
    fun `accepts every valid vector with the expected values`() {
        val cases = vectors.getValue("valid").jsonArray
        assertTrue("no valid vectors", cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val uri = case.getValue("uri").jsonPrimitive.content
            val e = case.getValue("expected").jsonObject
            val expected = PairingInfo(
                version = e.getValue("version").jsonPrimitive.int,
                hosts = e.getValue("hosts").jsonArray.map { it.jsonPrimitive.content },
                port = e.getValue("port").jsonPrimitive.int,
                token = e.getValue("token").jsonPrimitive.content,
                fingerprint = e.getValue("fingerprint").jsonPrimitive.content,
                name = e.getValue("name").let { if (it is JsonNull) null else it.jsonPrimitive.content },
            )
            when (val result = PairingUri.parse(uri, supported)) {
                is PairingUriResult.Valid -> assertEquals("case \"$name\"", expected, result.info)
                is PairingUriResult.Invalid -> fail("case \"$name\" rejected with ${result.error}")
            }
        }
    }

    @Test
    fun `rejects every invalid vector with the expected error`() {
        val cases = vectors.getValue("invalid").jsonArray
        assertTrue("no invalid vectors", cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val uri = case.getValue("uri").jsonPrimitive.content
            val expected = PairingUriError.valueOf(case.getValue("error").jsonPrimitive.content)
            when (val result = PairingUri.parse(uri, supported)) {
                is PairingUriResult.Valid -> fail("case \"$name\" was accepted: ${result.info}")
                is PairingUriResult.Invalid -> assertEquals("case \"$name\"", expected, result.error)
            }
        }
    }

    @Test
    fun `error enum matches the shared list and order`() {
        val shared = SharedFixtures.json("constants.json").getValue("pairingUriErrors").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(shared, PairingUriError.entries.map { it.name })
    }

    @Test
    fun `malformed percent-encoding in the name is rejected`() {
        val uri = "openscreen://pair?v=1&h=192.168.1.20&p=47820" +
            "&t=lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8" +
            "&f=Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8&n=%E5%B7"
        assertEquals(PairingUriResult.Invalid(PairingUriError.BAD_NAME), PairingUri.parse(uri))
    }
}

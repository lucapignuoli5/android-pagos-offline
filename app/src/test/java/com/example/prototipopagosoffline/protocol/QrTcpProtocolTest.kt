package com.example.prototipopagosoffline.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import com.google.gson.JsonParser

class QrTcpProtocolTest {
    @Test
    fun `amount parser uses exact cents and configured limit`() {
        assertEquals(1L, QrTcpProtocol.parseAmountCents("0,01"))
        assertEquals(12_345L, QrTcpProtocol.parseAmountCents("123.45"))
        assertEquals(100_000L, QrTcpProtocol.parseAmountCents("1000"))
        listOf("", "0", "-1", "1.001", "1000.01", "NaN", "1e2").forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                QrTcpProtocol.parseAmountCents(value)
            }
        }
    }

    @Test
    fun `bounded line rejects incomplete excessive and carriage return input`() {
        assertEquals("{}", QrTcpProtocol.readLine(ByteArrayInputStream("{}\n".toByteArray())))
        assertThrows(IllegalArgumentException::class.java) {
            QrTcpProtocol.readLine(ByteArrayInputStream("{}".toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrTcpProtocol.readLine(ByteArrayInputStream("{}\r\n".toByteArray()))
        }
        val excessive = ("x".repeat(33) + "\n").toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            QrTcpProtocol.readLine(ByteArrayInputStream(excessive), 32)
        }
    }

    @Test
    fun `descriptor rejects missing defaulted and unknown fields`() {
        listOf(
            "{}",
            "{\"host\":\"192.168.1.2\",\"port\":8080}",
            "{\"host\":123,\"operation_id\":\"00000000-0000-4000-8000-000000000000\",\"port\":8080,\"request_envelope_b64\":\"AA==\",\"transport\":\"tcp\",\"version\":2}",
            "{\"host\":\"192.168.1.2\",\"operation_id\":\"00000000-0000-4000-8000-000000000000\",\"port\":8080,\"request_envelope_b64\":\"AA==\",\"transport\":\"tcp\",\"unexpected\":true,\"version\":2}"
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { QrTcpProtocol.decodeDescriptor(text) }
        }
    }

    @Test
    fun `descriptor round trip preserves exact signed request`() {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("protocol_v2_vectors.json"))
        val vector = stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        val envelope = ProtocolV2.encodeSignedEnvelope(
            vector["request"].asJsonObject,
            vector["signature_der_b64"].asString
        )

        val encoded = QrTcpProtocol.encodeDescriptor("192.168.1.20", 8080, envelope)
        val decoded = QrTcpProtocol.decodeDescriptor(encoded)

        assertEquals("192.168.1.20", decoded.host)
        assertEquals(8080, decoded.port)
        assertEquals(vector["request"].asJsonObject["operation_id"].asString, decoded.operationId)
        org.junit.Assert.assertArrayEquals(envelope, decoded.requestEnvelope)
    }
}

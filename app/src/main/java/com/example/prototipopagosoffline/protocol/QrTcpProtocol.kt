package com.example.prototipopagosoffline.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import com.example.prototipopagosoffline.utils.MobilePosCoordinator

data class QrPaymentDescriptor(
    val host: String,
    val port: Int,
    val operationId: String,
    val requestEnvelope: ByteArray
)

object QrTcpProtocol {
    const val CONNECT_TIMEOUT_MILLIS = 5_000
    const val SOCKET_TIMEOUT_MILLIS = 5_000
    const val TOTAL_DEADLINE_MILLIS = 15_000L
    const val MAX_TCP_LINE_BYTES = 8 * 1024
    const val MAX_QR_BYTES = 3_000

    fun encodeDescriptor(host: String, port: Int, requestEnvelope: ByteArray): String {
        requireValidHost(host)
        require(port in 1..65535)
        val request = ProtocolV2.parseSignedEnvelope(requestEnvelope)
        require(request.payload["type"].asString == "payment_request")
        val operationId = request.payload["operation_id"].asString
        val value = JsonObject().apply {
            addProperty("host", host)
            addProperty("operation_id", operationId)
            addProperty("port", port)
            addProperty("request_envelope_b64", Base64.getEncoder().encodeToString(requestEnvelope))
            addProperty("transport", "tcp")
            addProperty("version", ProtocolV2.VERSION)
        }
        val encoded = canonicalTransportJson(value)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_QR_BYTES) { "Solicitud demasiado grande para QR" }
        return encoded
    }

    fun decodeDescriptor(text: String): QrPaymentDescriptor {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_QR_BYTES) { "QR demasiado grande" }
        val value = parseObject(text, DESCRIPTOR_FIELDS)
        require(strictInt(value, "version") == ProtocolV2.VERSION)
        require(strictString(value, "transport") == "tcp")
        val host = strictString(value, "host").also(::requireValidHost)
        val port = strictInt(value, "port").also { require(it in 1..65535) }
        val operationId = canonicalOperationId(strictString(value, "operation_id"))
        val requestEnvelope = decodeEnvelope(value, "request_envelope_b64")
        val request = ProtocolV2.parseSignedEnvelope(requestEnvelope)
        require(request.payload["type"].asString == "payment_request")
        require(request.payload["operation_id"].asString == operationId)
        return QrPaymentDescriptor(host, port, operationId, requestEnvelope)
    }

    fun encodeReceipt(operationId: String, envelope: ByteArray): String =
        encodeFrame("payment_receipt", operationId, "receipt_envelope_b64", envelope)

    fun decodeReceipt(text: String): Pair<String, ByteArray> =
        decodeFrame(text, "payment_receipt", "receipt_envelope_b64", MessageType.PAYMENT_RECEIPT)

    fun encodeAck(operationId: String, envelope: ByteArray): String =
        encodeFrame("payment_ack", operationId, "ack_envelope_b64", envelope)

    fun decodeAck(text: String): Pair<String, ByteArray> =
        decodeFrame(text, "payment_ack", "ack_envelope_b64", MessageType.PAYMENT_ACK)

    fun readLine(input: InputStream, maximumBytes: Int = MAX_TCP_LINE_BYTES): String {
        require(maximumBytes in 1..MAX_TCP_LINE_BYTES)
        val output = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            require(next != -1) { "Mensaje TCP incompleto" }
            if (next == '\n'.code) break
            require(next != '\r'.code) { "Retorno de carro no admitido" }
            require(output.size() < maximumBytes) { "Mensaje TCP demasiado grande" }
            output.write(next)
        }
        require(output.size() > 0) { "Mensaje TCP vacio" }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    fun writeLine(output: OutputStream, value: String) {
        require(!value.contains('\n') && !value.contains('\r'))
        val data = value.toByteArray(Charsets.UTF_8)
        require(data.isNotEmpty() && data.size <= MAX_TCP_LINE_BYTES)
        output.write(data)
        output.write('\n'.code)
        output.flush()
    }

    fun parseAmountCents(value: String): Long {
        val normalized = value.trim().replace(',', '.')
        require(Regex("^(0|[1-9]\\d{0,3})(\\.\\d{1,2})?$").matches(normalized)) {
            "Importe invalido"
        }
        val cents = BigDecimal(normalized).movePointRight(2).longValueExact()
        require(cents in 1..ProtocolV2.MAX_AMOUNT_CENTS) { "Importe fuera del limite" }
        return cents
    }

    private fun encodeFrame(type: String, operationId: String, field: String, envelope: ByteArray): String {
        canonicalOperationId(operationId)
        val parsed = ProtocolV2.parseSignedEnvelope(envelope)
        require(parsed.payload["operation_id"].asString == operationId)
        require(parsed.payload["type"].asString == type)
        val value = JsonObject().apply {
            addProperty(field, Base64.getEncoder().encodeToString(envelope))
            addProperty("operation_id", operationId)
            addProperty("type", type)
            addProperty("version", ProtocolV2.VERSION)
        }
        return canonicalTransportJson(value).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_TCP_LINE_BYTES)
        }
    }

    private fun decodeFrame(
        text: String,
        expectedType: String,
        field: String,
        messageType: MessageType
    ): Pair<String, ByteArray> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_TCP_LINE_BYTES)
        val value = parseObject(text, setOf("version", "type", "operation_id", field))
        require(strictInt(value, "version") == ProtocolV2.VERSION)
        require(strictString(value, "type") == expectedType)
        val operationId = canonicalOperationId(strictString(value, "operation_id"))
        val envelope = decodeEnvelope(value, field)
        val parsed = ProtocolV2.parseSignedEnvelope(envelope)
        require(ProtocolV2.validatePayload(parsed.payload) == messageType)
        require(parsed.payload["operation_id"].asString == operationId)
        return operationId to envelope
    }

    private fun parseObject(text: String, fields: Set<String>): JsonObject {
        val parsed = JsonParser.parseString(text)
        require(parsed.isJsonObject) { "Se esperaba un objeto JSON" }
        val value = parsed.asJsonObject
        require(value.keySet() == fields) { "Campos TCP/QR incompatibles" }
        require(canonicalTransportJson(value) == text) { "JSON TCP/QR no canonico" }
        return value
    }

    private fun decodeEnvelope(value: JsonObject, field: String): ByteArray {
        val encoded = value[field]
        require(encoded != null && encoded.isJsonPrimitive && encoded.asJsonPrimitive.isString)
        val text = encoded.asString
        require(!text.contains('\n') && !text.contains('\r'))
        return try {
            Base64.getDecoder().decode(text).also {
                require(it.isNotEmpty() && it.size <= ProtocolV2.MAX_MESSAGE_BYTES)
            }
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Sobre Base64 invalido", exception)
        }
    }

    private fun strictInt(value: JsonObject, field: String): Int {
        val element = value[field]
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
        val text = element.asJsonPrimitive.asString
        require(Regex("^(0|[1-9]\\d*)$").matches(text))
        return text.toInt()
    }

    private fun strictString(value: JsonObject, field: String): String {
        val element = value[field]
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString)
        return element.asString
    }

    private fun canonicalTransportJson(value: JsonObject): String = GSON.toJson(canonicalElement(value))

    private fun canonicalElement(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { result ->
            element.asJsonObject.keySet().sorted().forEach { key ->
                result.add(key, canonicalElement(element.asJsonObject[key]))
            }
        }
        element.isJsonArray -> JsonArray().also { result ->
            element.asJsonArray.forEach { result.add(canonicalElement(it)) }
        }
        else -> element.deepCopy()
    }

    private fun canonicalOperationId(value: String): String {
        val uuid = UUID.fromString(value)
        require(uuid.version() == 4 && uuid.toString() == value)
        return value
    }

    private fun requireValidHost(host: String) {
        val parts = host.split('.')
        require(parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                (part == "0" || !part.startsWith('0')) && part.toInt() in 0..255
        }) { "Direccion IPv4 invalida" }
    }

    private val DESCRIPTOR_FIELDS = setOf(
        "version", "transport", "host", "port", "operation_id", "request_envelope_b64"
    )
    private val GSON = Gson()
}

class QrTcpClient(
    private val exchangeStore: NfcExchangeStore,
    private val exchangeService: ClientPaymentExchangeService
) {
    suspend fun deliver(descriptor: QrPaymentDescriptor, receiptEnvelope: ByteArray): ByteArray {
        val receipt = ProtocolV2.parseSignedEnvelope(receiptEnvelope)
        require(receipt.payload["operation_id"].asString == descriptor.operationId)
        return withTimeout(QrTcpProtocol.TOTAL_DEADLINE_MILLIS) { Socket().use { socket ->
            socket.connect(
                InetSocketAddress(descriptor.host, descriptor.port),
                QrTcpProtocol.CONNECT_TIMEOUT_MILLIS
            )
            socket.soTimeout = QrTcpProtocol.SOCKET_TIMEOUT_MILLIS
            socket.tcpNoDelay = true
            exchangeStore.markDeliveryStarted(descriptor.operationId)
            QrTcpProtocol.writeLine(
                socket.getOutputStream(),
                QrTcpProtocol.encodeReceipt(descriptor.operationId, receiptEnvelope)
            )
            val (operationId, ack) = QrTcpProtocol.decodeAck(
                QrTcpProtocol.readLine(socket.getInputStream())
            )
            require(operationId == descriptor.operationId)
            exchangeService.acceptAck(ack)
            ack
        } }
    }
}

class QrTcpServer(
    private val coordinator: MobilePosCoordinator
) {
    suspend fun serve(port: Int, operationId: String, onAccepted: suspend (String) -> Unit) {
        require(port in 1..65535)
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            server.soTimeout = 1_000
            while (true) {
                coroutineContext.ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                socket.use { client ->
                    try {
                        withTimeout(QrTcpProtocol.TOTAL_DEADLINE_MILLIS) {
                            client.soTimeout = QrTcpProtocol.SOCKET_TIMEOUT_MILLIS
                            client.tcpNoDelay = true
                            val (receivedOperationId, receipt) = QrTcpProtocol.decodeReceipt(
                                QrTcpProtocol.readLine(client.getInputStream())
                            )
                            require(receivedOperationId == operationId)
                            val ack = coordinator.acceptReceipt(operationId, receipt)
                            QrTcpProtocol.writeLine(
                                client.getOutputStream(),
                                QrTcpProtocol.encodeAck(operationId, ack)
                            )
                            onAccepted(operationId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // La conexion se cierra sin convertir entrada invalida en una aceptacion.
                    }
                }
            }
        }
    }
}

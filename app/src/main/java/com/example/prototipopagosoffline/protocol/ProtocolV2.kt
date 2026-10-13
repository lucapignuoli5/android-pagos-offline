package com.example.prototipopagosoffline.protocol

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

object ProtocolV2 {
    const val VERSION = 2
    const val CURRENCY = "ARS"
    const val MAX_MESSAGE_BYTES = 4 * 1024
    const val DEFAULT_CHUNK_BYTES = 160
    const val MAX_AMOUNT_CENTS = 100_000L
    const val MAX_REQUEST_LIFETIME_SECONDS = 120L

    val AID: ByteArray = hex("F222222222")
    val SELECT_APDU: ByteArray = hex("00A4040005F222222222")
    const val CLA_TFG = 0x80
    const val INS_PUT_REQUEST = 0x10
    const val INS_GET_STATUS = 0x20
    const val INS_GET_RECEIPT = 0x30
    const val INS_PUT_ACK = 0x40
    const val P2_MORE = 0x00
    const val P2_LAST = 0x01
    val SW_OK: ByteArray = hex("9000")
    val SW_BAD_DATA: ByteArray = hex("6A80")
    val SW_NOT_FOUND: ByteArray = hex("6A82")
    val SW_CONDITIONS_NOT_SATISFIED: ByteArray = hex("6985")

    private val gson = Gson()
    private val identifierPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
    private val digestPattern = Regex("^[0-9a-f]{64}$")
    private val noncePattern = Regex("^[A-Za-z0-9_-]{22}$")
    private val timestampPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
    private val p256Order = java.math.BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        16
    )

    private val commonFields = setOf(
        "version", "type", "operation_id", "merchant_id", "terminal_id",
        "amount_cents", "currency", "nonce", "issued_at", "expires_at"
    )
    private val requestFields = commonFields + setOf("pos_key_id", "pos_key_fingerprint")
    private val receiptFields = commonFields + setOf(
        "wallet_id", "offline_authorization_id", "wallet_key_id",
        "wallet_key_fingerprint", "request_digest"
    )
    private val ackFields = commonFields + setOf(
        "wallet_id", "offline_authorization_id", "pos_key_id", "pos_key_fingerprint",
        "receipt_digest", "accepted_at", "status"
    )

    fun canonicalJson(payload: JsonObject): String {
        validateNoFloatingPoint(payload)
        val canonical = canonicalElement(payload)
        val encoded = gson.toJson(canonical)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "El mensaje supera el maximo logico de 4 KiB"
        }
        return encoded
    }

    fun parseCanonicalPayload(data: ByteArray): JsonObject {
        require(data.size <= MAX_MESSAGE_BYTES) { "El mensaje supera el maximo logico de 4 KiB" }
        val text = data.toString(Charsets.UTF_8)
        val parsed = JsonParser.parseString(text)
        require(parsed.isJsonObject) { "El payload debe ser un objeto JSON" }
        val payload = parsed.asJsonObject
        validatePayload(payload)
        require(canonicalJson(payload) == text) { "El JSON recibido no es canonico" }
        return payload
    }

    fun validatePayload(payload: JsonObject): MessageType {
        val type = MessageType.from(payload.string("type"))
        val expected = when (type) {
            MessageType.PAYMENT_REQUEST -> requestFields
            MessageType.PAYMENT_RECEIPT -> receiptFields
            MessageType.PAYMENT_ACK -> ackFields
        }
        require(payload.keySet() == expected) {
            "Campos incompatibles; faltan=${expected - payload.keySet()}, sobran=${payload.keySet() - expected}"
        }
        require(payload.integer("version") == VERSION.toLong()) { "Version no soportada" }
        val operationId = payload.string("operation_id")
        val uuid = UUID.fromString(operationId)
        require(uuid.toString() == operationId && uuid.version() == 4) {
            "operation_id debe ser UUID v4 canonico"
        }
        identifier(payload.string("merchant_id"), "merchant_id")
        identifier(payload.string("terminal_id"), "terminal_id")
        val amount = payload.integer("amount_cents")
        require(amount in 1..MAX_AMOUNT_CENTS) { "amount_cents fuera del limite" }
        require(payload.string("currency") == CURRENCY) { "Solo se admite ARS" }
        validateNonce(payload.string("nonce"))
        val issuedAt = timestamp(payload.string("issued_at"), "issued_at")
        val expiresAt = timestamp(payload.string("expires_at"), "expires_at")
        require(expiresAt.isAfter(issuedAt)) { "expires_at debe ser posterior a issued_at" }

        when (type) {
            MessageType.PAYMENT_REQUEST -> {
                require(expiresAt.epochSecond - issuedAt.epochSecond <= MAX_REQUEST_LIFETIME_SECONDS) {
                    "La solicitud supera los dos minutos"
                }
                keyReference(payload, "pos")
            }
            MessageType.PAYMENT_RECEIPT -> {
                identifier(payload.string("wallet_id"), "wallet_id")
                identifier(payload.string("offline_authorization_id"), "offline_authorization_id")
                keyReference(payload, "wallet")
                digest(payload.string("request_digest"), "request_digest")
            }
            MessageType.PAYMENT_ACK -> {
                identifier(payload.string("wallet_id"), "wallet_id")
                identifier(payload.string("offline_authorization_id"), "offline_authorization_id")
                keyReference(payload, "pos")
                digest(payload.string("receipt_digest"), "receipt_digest")
                require(!timestamp(payload.string("accepted_at"), "accepted_at").isBefore(issuedAt)) {
                    "accepted_at no puede preceder a issued_at"
                }
                require(payload.string("status") == "LOCALLY_ACCEPTED") { "Estado de acuse no soportado" }
            }
        }
        canonicalJson(payload)
        return type
    }

    fun signPayload(payload: JsonObject, privateKey: PrivateKey): String {
        require(privateKey is ECPrivateKey) { "La clave privada debe ser EC" }
        require(privateKey.params.order == p256Order && privateKey.params.curve.field.fieldSize == 256) {
            "La clave debe usar ECDSA P-256"
        }
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(canonicalJson(payload).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun verifyPayloadSignature(payload: JsonObject, signatureBase64: String, publicKeyBase64: String): Boolean {
        return try {
            val publicDer = Base64.getDecoder().decode(publicKeyBase64)
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicDer)) as ECPublicKey
            requireP256(publicKey)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(canonicalJson(payload).toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.getDecoder().decode(signatureBase64))
        } catch (_: Exception) {
            false
        }
    }

    fun publicKeyFingerprint(publicKeyDer: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(publicKeyDer)
        .joinToString("") { "%02x".format(it) }

    fun payloadDigest(payload: JsonObject): String = MessageDigest
        .getInstance("SHA-256")
        .digest(canonicalJson(payload).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun buildApdu(instruction: Int, sequence: Int, isLast: Boolean, data: ByteArray = byteArrayOf()): ByteArray {
        require(instruction in supportedInstructions()) { "Instruccion APDU no soportada" }
        require(sequence in 0..255) { "Secuencia APDU fuera de rango" }
        require(data.size <= 255) { "APDU corto demasiado grande" }
        return byteArrayOf(
            CLA_TFG.toByte(), instruction.toByte(), sequence.toByte(),
            (if (isLast) P2_LAST else P2_MORE).toByte(), data.size.toByte()
        ) + data
    }

    fun parseApdu(apdu: ByteArray): ApduCommand {
        require(apdu.size >= 5 && apdu[0].toInt() and 0xff == CLA_TFG) { "Cabecera APDU invalida" }
        val instruction = apdu[1].toInt() and 0xff
        val sequence = apdu[2].toInt() and 0xff
        val p2 = apdu[3].toInt() and 0xff
        val length = apdu[4].toInt() and 0xff
        require(instruction in supportedInstructions()) { "Instruccion APDU no soportada" }
        require(p2 == P2_MORE || p2 == P2_LAST) { "Indicador APDU invalido" }
        require(apdu.size == length + 5) { "Longitud APDU invalida" }
        return ApduCommand(instruction, sequence, p2 == P2_LAST, apdu.copyOfRange(5, apdu.size))
    }

    fun chunkMessage(data: ByteArray, chunkSize: Int = DEFAULT_CHUNK_BYTES): List<ByteArray> {
        require(data.isNotEmpty() && data.size <= MAX_MESSAGE_BYTES) { "Longitud logica invalida" }
        require(chunkSize in 1..255) { "Tamano de fragmento invalido" }
        return data.asList().chunked(chunkSize).map { chunk -> chunk.toByteArray() }
    }

    fun assembleChunks(chunks: List<ByteArray>): ByteArray {
        val data = chunks.fold(byteArrayOf()) { accumulated, chunk -> accumulated + chunk }
        require(data.isNotEmpty() && data.size <= MAX_MESSAGE_BYTES) { "Longitud logica invalida" }
        return data
    }

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

    private fun validateNoFloatingPoint(element: JsonElement) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { validateNoFloatingPoint(it.value) }
            element.isJsonArray -> element.asJsonArray.forEach(::validateNoFloatingPoint)
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                val text = element.asJsonPrimitive.asString
                require(!text.contains('.') && !text.contains('e', true)) { "No se permiten flotantes" }
            }
        }
    }

    private fun JsonObject.string(field: String): String {
        val element = get(field)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$field debe ser texto"
        }
        return element.asString
    }

    private fun JsonObject.integer(field: String): Long {
        val element = get(field)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "$field debe ser entero"
        }
        val text = element.asJsonPrimitive.asString
        require(Regex("^-?(0|[1-9]\\d*)$").matches(text)) { "$field debe ser entero" }
        return text.toLong()
    }

    private fun identifier(value: String, field: String) {
        require(identifierPattern.matches(value)) { "$field es invalido" }
    }

    private fun digest(value: String, field: String) {
        require(digestPattern.matches(value)) { "$field debe ser SHA-256 hexadecimal" }
    }

    private fun keyReference(payload: JsonObject, prefix: String) {
        identifier(payload.string("${prefix}_key_id"), "${prefix}_key_id")
        digest(payload.string("${prefix}_key_fingerprint"), "${prefix}_key_fingerprint")
    }

    private fun validateNonce(value: String) {
        require(noncePattern.matches(value)) { "nonce debe ser Base64url sin relleno" }
        require(Base64.getUrlDecoder().decode("$value==").size == 16) { "nonce debe contener 128 bits" }
    }

    private fun timestamp(value: String, field: String): Instant {
        require(timestampPattern.matches(value)) { "$field debe estar en UTC sin fracciones" }
        return try {
            Instant.parse(value)
        } catch (exception: Exception) {
            throw IllegalArgumentException("$field es invalido", exception)
        }
    }

    private fun requireP256(publicKey: ECPublicKey) {
        require(publicKey.params.order == p256Order && publicKey.params.curve.field.fieldSize == 256) {
            "La clave debe usar ECDSA P-256"
        }
    }

    private fun supportedInstructions() = setOf(INS_PUT_REQUEST, INS_GET_STATUS, INS_GET_RECEIPT, INS_PUT_ACK)

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

enum class MessageType(val wireValue: String) {
    PAYMENT_REQUEST("payment_request"),
    PAYMENT_RECEIPT("payment_receipt"),
    PAYMENT_ACK("payment_ack");

    companion object {
        fun from(value: String): MessageType = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Tipo de mensaje no soportado")
    }
}

data class ApduCommand(
    val instruction: Int,
    val sequence: Int,
    val isLast: Boolean,
    val data: ByteArray
)

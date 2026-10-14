package com.example.prototipopagosoffline.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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

/** Pure contract functions. Persistence, authorization issuance and transports are separate phases. */
object ProtocolV3 {
    const val VERSION = 3
    const val CURRENCY = "ARS"
    const val MAX_MESSAGE_BYTES = 4096
    const val DEFAULT_CHUNK_BYTES = 160
    const val MAX_REQUEST_LIFETIME_SECONDS = 120L
    const val INS_GET_AUTHORIZATION = 0x50
    val AID: ByteArray get() = ProtocolV2.AID.copyOf()
    val SELECT_APDU: ByteArray get() = ProtocolV2.SELECT_APDU.copyOf()
    val SELECT_CAPABILITIES: ByteArray get() =
        "{\"authorization_instruction\":80,\"chunk_bytes\":160,\"max_message_bytes\":4096,\"protocol_version\":3}".toByteArray(Charsets.UTF_8)

    private val integerPattern = Regex("^(0|-?[1-9][0-9]*)$")
    private val identifierPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
    private val digestPattern = Regex("^[0-9a-f]{64}$")
    private val timestampPattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
    private val p256Order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    fun canonicalJson(value: JsonElement): String = buildString { appendCanonical(value) }.also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Mensaje mayor a 4096 bytes" }
    }

    fun parseCanonicalPayload(data: ByteArray): JsonObject {
        val parsed = parseJson(data)
        require(parsed.isJsonObject) { "El payload debe ser un objeto" }
        val payload = parsed.asJsonObject
        validatePayload(payload)
        require(canonicalJson(payload).toByteArray(Charsets.UTF_8).contentEquals(data)) { "Payload no canonico" }
        return payload
    }

    fun encodeSignedEnvelope(payload: JsonObject, signatureBase64: String): ByteArray {
        validatePayload(payload)
        validateDerSignature(decodeBase64(signatureBase64, "signature_b64"))
        val envelope = JsonObject().apply {
            addProperty("payload_b64", Base64.getEncoder().encodeToString(canonicalJson(payload).toByteArray(Charsets.UTF_8)))
            addProperty("signature_b64", signatureBase64)
        }
        return canonicalJson(envelope).toByteArray(Charsets.UTF_8)
    }

    fun parseSignedEnvelope(data: ByteArray): SignedEnvelopeV3 {
        val parsed = parseJson(data)
        require(parsed.isJsonObject) { "El sobre debe ser un objeto" }
        val envelope = parsed.asJsonObject
        requireFields(envelope, setOf("payload_b64", "signature_b64"))
        require(canonicalJson(envelope).toByteArray(Charsets.UTF_8).contentEquals(data)) { "Sobre no canonico" }
        val payloadBytes = decodeBase64(envelope.string("payload_b64"), "payload_b64")
        val signature = envelope.string("signature_b64")
        validateDerSignature(decodeBase64(signature, "signature_b64"))
        return SignedEnvelopeV3(payloadBytes, parseCanonicalPayload(payloadBytes), signature)
    }

    fun signPayload(payload: JsonObject, privateKey: PrivateKey): String {
        validatePayload(payload)
        require(privateKey is ECPrivateKey && privateKey.params.order == p256Order &&
            privateKey.params.curve.field.fieldSize == 256 && privateKey.params.cofactor == 1) { "Clave fuera de P-256" }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(canonicalJson(payload).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    fun verifyExactSignature(payloadBytes: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean = try {
        val publicKey = decodePublicKey(publicKeyBase64)
        val signatureBytes = decodeBase64(signatureBase64, "signature_b64")
        validateDerSignature(signatureBytes)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payloadBytes)
        verifier.verify(signatureBytes)
    } catch (_: Exception) { false }

    fun payloadDigest(payloadBytes: ByteArray): String {
        require(payloadBytes.isNotEmpty() && payloadBytes.size <= MAX_MESSAGE_BYTES) { "Longitud de payload invalida" }
        return MessageDigest.getInstance("SHA-256").digest(payloadBytes).joinToString("") { "%02x".format(it) }
    }

    fun payloadDigest(envelope: SignedEnvelopeV3): String = payloadDigest(envelope.payloadBytes)

    fun publicKeyFingerprint(publicKeyBase64: String): String =
        payloadDigest(decodeBase64(publicKeyBase64, "public_key_b64").also { decodePublicKey(publicKeyBase64) })

    fun decodeBase64(value: String, field: String): ByteArray {
        require(value.isNotEmpty() && Regex("^[A-Za-z0-9+/]*={0,2}$").matches(value)) { "$field no es Base64 estandar" }
        val decoded = try { Base64.getDecoder().decode(value) } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$field no es Base64 valido", error)
        }
        require(Base64.getEncoder().encodeToString(decoded) == value) { "$field no es Base64 canonico" }
        return decoded
    }

    private fun decodePublicKey(publicKeyBase64: String): ECPublicKey {
        val encoded = decodeBase64(publicKeyBase64, "public_key_b64")
        val key = try { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) } catch (error: Exception) {
            throw IllegalArgumentException("Clave DER invalida o curva no soportada", error)
        } as? ECPublicKey
            ?: throw IllegalArgumentException("La clave debe ser EC")
        require(key.params.order == p256Order && key.params.curve.field.fieldSize == 256 && key.params.cofactor == 1) {
            "La clave debe ser P-256"
        }
        require(key.encoded.contentEquals(encoded)) { "Clave DER no canonica" }
        return key
    }

    private fun validateDerSignature(data: ByteArray) {
        require(data.size in 8..72 && data[0].toInt() and 255 == 0x30 && data[1].toInt() and 255 == data.size - 2) {
            "Firma DER no canonica"
        }
        var offset = 2
        repeat(2) {
            require(offset + 2 <= data.size && data[offset++].toInt() and 255 == 2) { "Firma DER invalida" }
            val length = data[offset++].toInt() and 255
            require(length in 1..33 && offset + length <= data.size) { "Firma DER invalida" }
            val number = data.copyOfRange(offset, offset + length)
            require(number[0].toInt() and 0x80 == 0) { "Entero DER negativo" }
            require(length == 1 || number[0] != 0.toByte() || number[1].toInt() and 0x80 != 0) { "Entero DER no minimo" }
            val scalar = BigInteger(1, number)
            require(scalar >= BigInteger.ONE && scalar < p256Order) { "Escalar ECDSA invalido" }
            offset += length
        }
        require(offset == data.size) { "Firma DER con datos sobrantes" }
    }

    private fun parseJson(data: ByteArray): JsonElement {
        require(data.isNotEmpty() && data.size <= MAX_MESSAGE_BYTES) { "Longitud JSON invalida" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch (error: Exception) { throw IllegalArgumentException("UTF-8 invalido", error) }
        return try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val parsed = readElement(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "JSON con datos sobrantes" }
                parsed
            }
        } catch (error: Exception) { throw IllegalArgumentException("JSON invalido", error) }
    }

    private fun readElement(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 12) { "JSON demasiado anidado" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName().also(::requireUnicode)
                    require(!result.has(name)) { "Campo JSON duplicado" }
                    result.add(name, readElement(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
                reader.beginArray()
                while (reader.hasNext()) result.add(readElement(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also(::requireUnicode))
            JsonToken.NUMBER -> {
                val text = reader.nextString()
                require(integerPattern.matches(text)) { "Solo se permiten enteros" }
                val integer = text.toLongOrNull() ?: throw IllegalArgumentException("Entero fuera de int64")
                JsonPrimitive(integer)
            }
            JsonToken.BOOLEAN, JsonToken.NULL -> throw IllegalArgumentException("Booleanos y null no permitidos")
            else -> throw IllegalArgumentException("Token JSON inesperado")
        }
    }

    private fun StringBuilder.appendCanonical(value: JsonElement) {
        when {
            value.isJsonObject -> {
                append('{')
                value.asJsonObject.keySet().sortedWith(CODEPOINT_ORDER).forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    appendQuoted(key); append(':'); appendCanonical(value.asJsonObject[key])
                }
                append('}')
            }
            value.isJsonArray -> {
                append('[')
                value.asJsonArray.forEachIndexed { index, element ->
                    if (index > 0) append(',')
                    appendCanonical(element)
                }
                append(']')
            }
            value.isJsonNull -> throw IllegalArgumentException("Null no permitido")
            value.asJsonPrimitive.isString -> appendQuoted(value.asString)
            value.asJsonPrimitive.isBoolean -> throw IllegalArgumentException("Booleanos no permitidos")
            value.asJsonPrimitive.isNumber -> {
                val text = value.asJsonPrimitive.asString
                require(integerPattern.matches(text) && text.toLongOrNull() != null) { "Solo enteros int64" }
                append(text)
            }
            else -> throw IllegalArgumentException("Valor JSON inesperado")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        requireUnicode(value)
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun requireUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                require(index < value.length && value[index].isLowSurrogate()) { "Unicode invalido" }
                index++
            } else require(!char.isLowSurrogate()) { "Unicode invalido" }
        }
    }

    private val CODEPOINT_ORDER = Comparator<String> { left, right ->
        val a = left.codePoints().toArray()
        val b = right.codePoints().toArray()
        var difference = 0
        for (index in 0 until minOf(a.size, b.size)) {
            difference = a[index].compareTo(b[index])
            if (difference != 0) break
        }
        if (difference == 0) a.size.compareTo(b.size) else difference
    }

    private fun requireFields(payload: JsonObject, fields: Set<String>) = require(payload.keySet() == fields) {
        "Campos incompatibles"
    }

    private fun JsonObject.string(field: String): String {
        val value = get(field)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$field debe ser texto" }
        return value.asString
    }

    private fun JsonObject.integer(field: String): Long {
        val value = get(field)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$field debe ser entero" }
        val text = value.asJsonPrimitive.asString
        require(integerPattern.matches(text)) { "$field debe ser entero" }
        return text.toLongOrNull() ?: throw IllegalArgumentException("$field fuera de int64")
    }

    private fun identifier(value: String) = require(identifierPattern.matches(value)) { "Identificador invalido" }
    private fun digest(value: String) = require(digestPattern.matches(value)) { "Digest SHA-256 invalido" }
    private fun timestamp(value: String): Instant {
        require(timestampPattern.matches(value)) { "Timestamp debe ser UTC sin fracciones" }
        require(value.take(4) != "0000") { "Timestamp invalido" }
        return try {
            Instant.parse(value).also { require(it.toString() == value) { "Timestamp no canonico" } }
        } catch (error: Exception) { throw IllegalArgumentException("Timestamp invalido", error) }
    }
    private fun uuid(value: String) {
        val parsed = try { UUID.fromString(value) } catch (error: Exception) { throw IllegalArgumentException("UUID invalido", error) }
        require(parsed.version() == 4 && parsed.toString() == value) { "UUID debe ser v4 canonico" }
    }

    private val commonFields = setOf("version", "type", "operation_id", "merchant_id", "terminal_id",
        "amount_cents", "currency", "nonce", "issued_at", "expires_at")
    private val fields = mapOf(
        MessageTypeV3.PAYMENT_REQUEST to commonFields + setOf("pos_key_id", "pos_key_fingerprint"),
        MessageTypeV3.OFFLINE_AUTHORIZATION to setOf("version", "type", "authorization_id", "wallet_id",
            "wallet_key_id", "wallet_key_fingerprint", "wallet_public_key_b64", "currency", "max_payment_cents",
            "max_payments", "authorized_total_cents", "max_request_lifetime_seconds", "issued_at",
            "spend_expires_at", "issuer_key_id", "issuer_key_fingerprint"),
        MessageTypeV3.PAYMENT_RECEIPT to commonFields + setOf("wallet_id", "offline_authorization_id",
            "wallet_key_id", "wallet_key_fingerprint", "request_digest", "authorization_digest", "issuance_index", "confirmed_at"),
        MessageTypeV3.PAYMENT_ACK to commonFields + setOf("wallet_id", "offline_authorization_id", "pos_key_id",
            "pos_key_fingerprint", "receipt_digest", "accepted_at", "status"),
        MessageTypeV3.OFFLINE_AUTHORIZATION_CLOSE to setOf("version", "type", "closure_id", "authorization_id",
            "wallet_id", "wallet_key_id", "wallet_key_fingerprint", "authorization_digest", "final_issuance_index",
            "emitted_total_cents", "manifest_digest", "issued_at"),
        MessageTypeV3.PAYMENT_RESOLUTION to setOf("version", "type", "operation_id", "wallet_id", "merchant_id",
            "terminal_id", "amount_cents", "currency", "offline_authorization_id", "authorization_digest",
            "request_digest", "receipt_digest", "operation_status", "code", "resolution_revision", "resolved_at",
            "issuer_key_id", "issuer_key_fingerprint"),
        MessageTypeV3.KEY_REGISTRATION to setOf("version", "type", "key_id", "purpose", "owner_user_id",
            "scope_id", "fingerprint", "nonce", "issued_at")
    )

    fun validatePayload(payload: JsonObject): MessageTypeV3 {
        val kind = MessageTypeV3.from(payload.string("type"))
        requireFields(payload, requireNotNull(fields[kind]))
        require(payload.integer("version") == VERSION.toLong()) { "Version no soportada" }
        listOf("operation_id", "closure_id").filter(payload::has).forEach { uuid(payload.string(it)) }
        listOf("authorization_id", "offline_authorization_id", "wallet_id", "merchant_id", "terminal_id",
            "wallet_key_id", "pos_key_id", "issuer_key_id", "key_id", "scope_id", "code")
            .filter(payload::has).forEach { identifier(payload.string(it)) }
        listOf("wallet_key_fingerprint", "pos_key_fingerprint", "issuer_key_fingerprint", "fingerprint",
            "request_digest", "receipt_digest", "authorization_digest", "manifest_digest")
            .filter(payload::has).forEach { digest(payload.string(it)) }
        listOf("amount_cents", "max_payment_cents", "max_payments", "authorized_total_cents", "issuance_index",
            "resolution_revision", "owner_user_id").filter(payload::has).forEach {
                require(payload.integer(it) > 0) { "$it debe ser positivo" }
            }
        listOf("final_issuance_index", "emitted_total_cents").filter(payload::has).forEach {
            require(payload.integer(it) >= 0) { "$it no puede ser negativo" }
        }
        if (payload.has("currency")) require(payload.string("currency") == CURRENCY) { "Solo ARS" }
        if (payload.has("nonce")) {
            val nonce = payload.string("nonce")
            require(Regex("^[A-Za-z0-9_-]{22}$").matches(nonce)) { "Nonce invalido" }
            val decoded = Base64.getUrlDecoder().decode("$nonce==")
            require(decoded.size == 16 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == nonce) {
                "Nonce no canonico"
            }
        }
        val times = listOf("issued_at", "expires_at", "confirmed_at", "accepted_at", "spend_expires_at", "resolved_at")
            .filter(payload::has).associateWith { timestamp(payload.string(it)) }
        if (payload.has("expires_at")) {
            val issued = requireNotNull(times["issued_at"])
            val expires = requireNotNull(times["expires_at"])
            require(expires.epochSecond - issued.epochSecond in 1..MAX_REQUEST_LIFETIME_SECONDS) { "Ventana de solicitud invalida" }
            listOf("confirmed_at", "accepted_at").filter(payload::has).forEach {
                require(requireNotNull(times[it]) in issued..expires) { "$it fuera de ventana" }
            }
        }
        when (kind) {
            MessageTypeV3.OFFLINE_AUTHORIZATION -> {
                require(requireNotNull(times["spend_expires_at"]) > requireNotNull(times["issued_at"])) { "Ventana grant invalida" }
                require(payload.integer("max_request_lifetime_seconds") in 1..MAX_REQUEST_LIFETIME_SECONDS) { "TTL grant invalido" }
                val product = BigInteger.valueOf(payload.integer("max_payment_cents")) * BigInteger.valueOf(payload.integer("max_payments"))
                require(product <= BigInteger.valueOf(Long.MAX_VALUE) && BigInteger.valueOf(payload.integer("authorized_total_cents")) <= product) {
                    "Limites de autorizacion incompatibles"
                }
                require(publicKeyFingerprint(payload.string("wallet_public_key_b64")) == payload.string("wallet_key_fingerprint")) {
                    "Huella wallet incompatible"
                }
            }
            MessageTypeV3.PAYMENT_ACK -> require(payload.string("status") == "LOCALLY_ACCEPTED") { "Estado acuse invalido" }
            MessageTypeV3.PAYMENT_RESOLUTION -> require(payload.string("operation_status") in setOf("ACCEPTED", "REJECTED")) {
                "Resolucion debe ser definitiva"
            }
            MessageTypeV3.KEY_REGISTRATION -> require(payload.string("purpose") in setOf("wallet", "pos")) { "Proposito invalido" }
            else -> Unit
        }
        canonicalJson(payload)
        return kind
    }

    private fun requireKind(envelope: SignedEnvelopeV3, kind: MessageTypeV3) {
        require(validatePayload(envelope.payload) == kind && canonicalJson(envelope.payload).toByteArray(Charsets.UTF_8)
            .contentEquals(envelope.payloadBytes)) { "Sobre de tipo incompatible o payload mutado" }
    }

    fun parseSelectCapabilities(data: ByteArray): Int {
        val parsed = parseJson(data)
        require(parsed.isJsonObject) { "Capacidades SELECT invalidas" }
        val capabilities = parsed.asJsonObject
        requireFields(capabilities, setOf("authorization_instruction", "chunk_bytes", "max_message_bytes", "protocol_version"))
        require(canonicalJson(capabilities).toByteArray(Charsets.UTF_8).contentEquals(data)) { "SELECT no canonico" }
        require(capabilities.integer("protocol_version") == VERSION.toLong() &&
            capabilities.integer("authorization_instruction") == INS_GET_AUTHORIZATION.toLong() &&
            capabilities.integer("max_message_bytes") == MAX_MESSAGE_BYTES.toLong()) { "Capacidades SELECT incompatibles" }
        val chunkBytes = capabilities.integer("chunk_bytes")
        require(chunkBytes in 1..DEFAULT_CHUNK_BYTES.toLong()) { "Fragmento SELECT no soportado" }
        return chunkBytes.toInt()
    }

    fun verifyAuthorization(grant: SignedEnvelopeV3, issuerPublicKeyBase64: String, expectedIssuerKeyId: String? = null): Boolean = try {
        requireKind(grant, MessageTypeV3.OFFLINE_AUTHORIZATION)
        grant.payload.string("issuer_key_fingerprint") == publicKeyFingerprint(issuerPublicKeyBase64) &&
            (expectedIssuerKeyId == null || grant.payload.string("issuer_key_id") == expectedIssuerKeyId) && grant.verify(issuerPublicKeyBase64)
    } catch (_: Exception) { false }

    fun verifyPosSigner(envelope: SignedEnvelopeV3, publicKeyBase64: String, keyId: String, fingerprint: String,
        merchantId: String, terminalId: String): Boolean = try {
        val kind = validatePayload(envelope.payload)
        require(kind == MessageTypeV3.PAYMENT_REQUEST || kind == MessageTypeV3.PAYMENT_ACK)
        requireKind(envelope, kind)
        envelope.payload.string("pos_key_id") == keyId && envelope.payload.string("pos_key_fingerprint") == fingerprint &&
            publicKeyFingerprint(publicKeyBase64) == fingerprint && envelope.payload.string("merchant_id") == merchantId &&
            envelope.payload.string("terminal_id") == terminalId && envelope.verify(publicKeyBase64)
    } catch (_: Exception) { false }

    private fun matching(left: JsonObject, right: JsonObject, selected: Collection<String>) = selected.forEach {
        require(canonicalJson(left[it]) == canonicalJson(right[it])) { "$it no coincide" }
    }

    fun validateReceiptBinding(request: SignedEnvelopeV3, receipt: SignedEnvelopeV3, grant: SignedEnvelopeV3) {
        requireKind(request, MessageTypeV3.PAYMENT_REQUEST)
        requireKind(receipt, MessageTypeV3.PAYMENT_RECEIPT)
        requireKind(grant, MessageTypeV3.OFFLINE_AUTHORIZATION)
        matching(request.payload, receipt.payload, commonFields - "type")
        val rp = receipt.payload
        val gp = grant.payload
        require(rp.string("request_digest") == payloadDigest(request) && rp.string("authorization_digest") == payloadDigest(grant)) {
            "Digest del comprobante incompatible"
        }
        require(rp.string("offline_authorization_id") == gp.string("authorization_id")) { "Autorizacion incompatible" }
        matching(rp, gp, listOf("wallet_id", "wallet_key_id", "wallet_key_fingerprint", "currency"))
        require(rp.integer("amount_cents") <= gp.integer("max_payment_cents") && rp.integer("amount_cents") <= gp.integer("authorized_total_cents")) {
            "Pago excede autorizacion"
        }
        require(rp.integer("issuance_index") <= gp.integer("max_payments")) { "Indice excede autorizacion" }
        val start = timestamp(gp.string("issued_at"))
        val end = timestamp(gp.string("spend_expires_at"))
        val issued = timestamp(rp.string("issued_at"))
        val expires = timestamp(rp.string("expires_at"))
        val confirmed = timestamp(rp.string("confirmed_at"))
        require(start <= issued && issued <= confirmed && confirmed <= expires && expires <= end) { "Pago fuera de ventana grant" }
        require(expires.epochSecond - issued.epochSecond <= gp.integer("max_request_lifetime_seconds")) { "Solicitud excede TTL firmado" }
        require(receipt.verify(gp.string("wallet_public_key_b64"))) { "Firma wallet ajena al grant" }
    }

    fun validateAckBinding(request: SignedEnvelopeV3, receipt: SignedEnvelopeV3, ack: SignedEnvelopeV3) {
        requireKind(request, MessageTypeV3.PAYMENT_REQUEST)
        requireKind(receipt, MessageTypeV3.PAYMENT_RECEIPT)
        requireKind(ack, MessageTypeV3.PAYMENT_ACK)
        matching(request.payload, receipt.payload, commonFields - "type")
        matching(receipt.payload, ack.payload, commonFields - "type")
        matching(receipt.payload, ack.payload, listOf("wallet_id", "offline_authorization_id"))
        matching(request.payload, ack.payload, listOf("pos_key_id", "pos_key_fingerprint"))
        require(receipt.payload.string("request_digest") == payloadDigest(request) && ack.payload.string("receipt_digest") == payloadDigest(receipt)) {
            "Digest del acuse incompatible"
        }
        require(timestamp(ack.payload.string("accepted_at")) >= timestamp(receipt.payload.string("confirmed_at"))) { "Acuse precede confirmacion" }
    }

    fun parseCloseManifest(data: ByteArray): List<JsonObject> {
        val parsed = parseJson(data)
        require(parsed.isJsonArray && canonicalJson(parsed).toByteArray(Charsets.UTF_8).contentEquals(data)) { "Manifest no canonico" }
        val seen = mutableSetOf<String>()
        return parsed.asJsonArray.mapIndexed { index, value ->
            require(value.isJsonObject) { "Entrada manifest invalida" }
            val entry = value.asJsonObject
            requireFields(entry, setOf("issuance_index", "operation_id", "amount_cents", "receipt_digest"))
            require(entry.integer("issuance_index") == index.toLong() + 1 && entry.integer("amount_cents") > 0) { "Indice/importe manifest invalido" }
            uuid(entry.string("operation_id"))
            require(seen.add(entry.string("operation_id"))) { "Operacion manifest duplicada" }
            digest(entry.string("receipt_digest"))
            entry
        }
    }

    fun validateCloseManifest(close: SignedEnvelopeV3, grant: SignedEnvelopeV3, manifestBytes: ByteArray): List<JsonObject> {
        requireKind(close, MessageTypeV3.OFFLINE_AUTHORIZATION_CLOSE)
        requireKind(grant, MessageTypeV3.OFFLINE_AUTHORIZATION)
        val cp = close.payload
        val gp = grant.payload
        matching(cp, gp, listOf("authorization_id", "wallet_id", "wallet_key_id", "wallet_key_fingerprint"))
        require(cp.string("authorization_digest") == payloadDigest(grant) && cp.string("manifest_digest") == payloadDigest(manifestBytes)) {
            "Digest cierre incompatible"
        }
        val manifest = parseCloseManifest(manifestBytes)
        val total = manifest.fold(BigInteger.ZERO) { sum, entry -> sum + BigInteger.valueOf(entry.integer("amount_cents")) }
        require(manifest.size.toLong() == cp.integer("final_issuance_index") && manifest.size.toLong() <= gp.integer("max_payments") &&
            total == BigInteger.valueOf(cp.integer("emitted_total_cents")) && total <= BigInteger.valueOf(gp.integer("authorized_total_cents")) &&
            manifest.all { it.integer("amount_cents") <= gp.integer("max_payment_cents") }) { "Totales cierre incompatibles" }
        require(timestamp(cp.string("issued_at")) >= timestamp(gp.string("issued_at"))) { "Cierre precede grant" }
        require(close.verify(gp.string("wallet_public_key_b64"))) { "Firma cierre incompatible" }
        return manifest
    }
}

data class SignedEnvelopeV3(val payloadBytes: ByteArray, val payload: JsonObject, val signatureBase64: String) {
    fun verify(publicKeyBase64: String): Boolean = ProtocolV3.verifyExactSignature(payloadBytes, signatureBase64, publicKeyBase64)
}

enum class MessageTypeV3(val wireValue: String) {
    PAYMENT_REQUEST("payment_request"), OFFLINE_AUTHORIZATION("offline_authorization"),
    PAYMENT_RECEIPT("payment_receipt"), PAYMENT_ACK("payment_ack"),
    OFFLINE_AUTHORIZATION_CLOSE("offline_authorization_close"), PAYMENT_RESOLUTION("payment_resolution"),
    KEY_REGISTRATION("key_registration");
    companion object {
        fun from(value: String): MessageTypeV3 = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Tipo de mensaje no soportado")
    }
}

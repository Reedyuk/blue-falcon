package dev.bluefalcon.plugins.nordicfota

/**
 * Minimal CBOR encoder for SMP protocol messages.
 *
 * Supports only the CBOR types required by SMP: unsigned integers,
 * byte strings, text strings, and maps. This avoids external CBOR
 * library dependencies.
 *
 * See RFC 8949 for the full CBOR specification.
 */
internal object CborEncoder {

    // CBOR major types (upper 3 bits of the initial byte)
    private const val MAJOR_UNSIGNED_INT = 0   // 0x00
    private const val MAJOR_BYTE_STRING = 2    // 0x40
    private const val MAJOR_TEXT_STRING = 3    // 0x60
    private const val MAJOR_MAP = 5            // 0xA0

    /**
     * Encode a CBOR map from key-value pairs.
     * Keys must be strings. Values can be Int, Long, ByteArray, or String.
     */
    fun encodeMap(entries: Map<String, Any>): ByteArray {
        val buffer = mutableListOf<Byte>()
        // Map header
        encodeTypeAndLength(buffer, MAJOR_MAP, entries.size)
        for ((key, value) in entries) {
            encodeTextString(buffer, key)
            when (value) {
                is Int -> encodeUnsignedInt(buffer, value.toLong())
                is Long -> encodeUnsignedInt(buffer, value)
                is ByteArray -> encodeByteString(buffer, value)
                is String -> encodeTextString(buffer, value)
                is Boolean -> encodeBoolean(buffer, value)
                else -> throw IllegalArgumentException("Unsupported CBOR value type: ${value::class}")
            }
        }
        return buffer.toByteArray()
    }

    /**
     * Encode a CBOR type and length prefix.
     */
    private fun encodeTypeAndLength(buffer: MutableList<Byte>, majorType: Int, length: Int) {
        val major = majorType shl 5
        when {
            length < 24 -> buffer.add((major or length).toByte())
            length < 256 -> {
                buffer.add((major or 24).toByte())
                buffer.add(length.toByte())
            }
            length < 65536 -> {
                buffer.add((major or 25).toByte())
                buffer.add((length shr 8).toByte())
                buffer.add(length.toByte())
            }
            else -> {
                buffer.add((major or 26).toByte())
                buffer.add((length shr 24).toByte())
                buffer.add((length shr 16).toByte())
                buffer.add((length shr 8).toByte())
                buffer.add(length.toByte())
            }
        }
    }

    private fun encodeUnsignedInt(buffer: MutableList<Byte>, value: Long) {
        encodeTypeAndLength(buffer, MAJOR_UNSIGNED_INT, value.toInt())
    }

    private fun encodeByteString(buffer: MutableList<Byte>, value: ByteArray) {
        encodeTypeAndLength(buffer, MAJOR_BYTE_STRING, value.size)
        buffer.addAll(value.toList())
    }

    private fun encodeTextString(buffer: MutableList<Byte>, value: String) {
        val bytes = value.encodeToByteArray()
        encodeTypeAndLength(buffer, MAJOR_TEXT_STRING, bytes.size)
        buffer.addAll(bytes.toList())
    }

    private fun encodeBoolean(buffer: MutableList<Byte>, value: Boolean) {
        // CBOR simple values: false = 0xF4, true = 0xF5
        buffer.add(if (value) 0xF5.toByte() else 0xF4.toByte())
    }
}

/**
 * Minimal CBOR decoder for SMP response messages.
 *
 * Supports decoding the CBOR types used in SMP responses: unsigned integers,
 * byte strings, text strings, maps, and arrays.
 */
internal object CborDecoder {

    /**
     * Decode a CBOR-encoded byte array into a map.
     * Returns null if the data is not a valid CBOR map.
     */
    fun decodeMap(data: ByteArray): Map<String, Any>? {
        if (data.isEmpty()) return null
        val state = DecoderState(data)
        return try {
            val result = decodeValue(state)
            @Suppress("UNCHECKED_CAST")
            result as? Map<String, Any>
        } catch (_: Exception) {
            null
        }
    }

    private class DecoderState(val data: ByteArray, var offset: Int = 0) {
        fun readByte(): Int {
            if (offset >= data.size) throw IllegalStateException("Unexpected end of CBOR data")
            return data[offset++].toInt() and 0xFF
        }

        fun readBytes(count: Int): ByteArray {
            if (offset + count > data.size) throw IllegalStateException("Unexpected end of CBOR data")
            val result = data.copyOfRange(offset, offset + count)
            offset += count
            return result
        }
    }

    private fun decodeValue(state: DecoderState): Any {
        val initial = state.readByte()
        val majorType = initial shr 5
        val additionalInfo = initial and 0x1F

        return when (majorType) {
            0 -> decodeLength(state, additionalInfo) // unsigned int
            2 -> { // byte string
                val length = decodeLength(state, additionalInfo).toInt()
                state.readBytes(length)
            }
            3 -> { // text string
                val length = decodeLength(state, additionalInfo).toInt()
                state.readBytes(length).decodeToString()
            }
            4 -> { // array
                val count = decodeLength(state, additionalInfo).toInt()
                val list = mutableListOf<Any>()
                repeat(count) { list.add(decodeValue(state)) }
                list
            }
            5 -> { // map
                val count = decodeLength(state, additionalInfo).toInt()
                val map = mutableMapOf<String, Any>()
                repeat(count) {
                    val key = decodeValue(state).toString()
                    val value = decodeValue(state)
                    map[key] = value
                }
                map
            }
            7 -> { // simple values and floats
                when (additionalInfo) {
                    20 -> false
                    21 -> true
                    22 -> Unit // null
                    else -> additionalInfo.toLong()
                }
            }
            else -> throw IllegalStateException("Unsupported CBOR major type: $majorType")
        }
    }

    private fun decodeLength(state: DecoderState, additionalInfo: Int): Long {
        return when {
            additionalInfo < 24 -> additionalInfo.toLong()
            additionalInfo == 24 -> state.readByte().toLong()
            additionalInfo == 25 -> {
                val b1 = state.readByte().toLong()
                val b2 = state.readByte().toLong()
                (b1 shl 8) or b2
            }
            additionalInfo == 26 -> {
                val b1 = state.readByte().toLong()
                val b2 = state.readByte().toLong()
                val b3 = state.readByte().toLong()
                val b4 = state.readByte().toLong()
                (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
            }
            else -> throw IllegalStateException("Unsupported CBOR additional info: $additionalInfo")
        }
    }
}

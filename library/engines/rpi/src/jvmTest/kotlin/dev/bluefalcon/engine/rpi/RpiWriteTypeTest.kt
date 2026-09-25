package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothGattCharacteristic.WriteType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [RpiEngine.resolveWriteType], the mapping from the legacy `Int?` write type to a
 * Blessed write type. The values follow Android's constants, which the other engines also read:
 * 1 is `WRITE_TYPE_NO_RESPONSE` and 2 is `WRITE_TYPE_DEFAULT`.
 */
class RpiWriteTypeTest {

    @Test
    fun `null uses a write with response when the characteristic supports one`() {
        assertEquals(WriteType.WITH_RESPONSE, resolve(null, withResponse = true, withoutResponse = false))
        assertEquals(WriteType.WITH_RESPONSE, resolve(null, withResponse = true, withoutResponse = true))
    }

    @Test
    fun `null uses a write without response when that is the only write the characteristic supports`() {
        assertEquals(WriteType.WITHOUT_RESPONSE, resolve(null, withResponse = false, withoutResponse = true))
    }

    @Test
    fun `WRITE_TYPE_DEFAULT is a write with response`() {
        assertEquals(WriteType.WITH_RESPONSE, resolve(2, withResponse = true, withoutResponse = true))
    }

    @Test
    fun `WRITE_TYPE_NO_RESPONSE is a write without response`() {
        assertEquals(WriteType.WITHOUT_RESPONSE, resolve(1, withResponse = true, withoutResponse = true))
    }

    @Test
    fun `0 stays a write with response`() {
        assertEquals(WriteType.WITH_RESPONSE, resolve(0, withResponse = true, withoutResponse = true))
    }

    @Test
    fun `an explicit write type is not changed by the characteristic properties`() {
        // Blessed refuses a write type that the characteristic does not support, and the engine then
        // throws, so an explicit request is never changed into a different write.
        assertEquals(WriteType.WITH_RESPONSE, resolve(2, withResponse = false, withoutResponse = true))
        assertEquals(WriteType.WITHOUT_RESPONSE, resolve(1, withResponse = true, withoutResponse = false))
    }

    private fun resolve(writeType: Int?, withResponse: Boolean, withoutResponse: Boolean): WriteType =
        RpiEngine.resolveWriteType(writeType, withResponse, withoutResponse)
}

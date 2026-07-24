package dev.bluefalcon.peripheral.android

import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AndroidBluetoothStackModelTest {
    @Test
    fun writeEventCopiesCallbackPayload() {
        val source = byteArrayOf(1, 2)
        val event = AndroidGattEvent.CharacteristicWrite(
            sessionId = PeripheralSessionId("central"),
            requestId = 7,
            serviceId = GattServiceId(Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb")),
            characteristicId = GattCharacteristicId(Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb")),
            offset = 0,
            preparedWrite = false,
            responseNeeded = true,
            value = source,
        )

        source[0] = 99

        assertContentEquals(byteArrayOf(1, 2), event.value)
    }
}

package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplePeripheralStackModelTest {

    @Test
    fun batchEventCopiesListAndEveryPayload() {
        val first = byteArrayOf(1, 2)
        val source = mutableListOf(
            AppleCharacteristicWrite(ServiceId, CharacteristicId, 0, first),
            AppleCharacteristicWrite(ServiceId, OtherCharacteristicId, 2, byteArrayOf(3)),
        )
        val event = AppleGattEvent.CharacteristicWriteBatch(
            sessionId = SessionId,
            maximumUpdateValueLength = 180,
            requestToken = AppleRequestToken(7),
            writes = source,
        )

        first[0] = 99
        source.clear()

        assertEquals(2, event.writes.size)
        assertContentEquals(byteArrayOf(1, 2), event.writes.first().value)
        event.writes.first().value[0] = 88
        assertContentEquals(byteArrayOf(1, 2), event.writes.first().value)
    }

    @Test
    fun batchEventRejectsEmptyWrites() {
        assertFailsWith<IllegalArgumentException> {
            AppleGattEvent.CharacteristicWriteBatch(
                sessionId = SessionId,
                maximumUpdateValueLength = 180,
                requestToken = AppleRequestToken(7),
                writes = emptyList(),
            )
        }
    }

    @Test
    fun responseCopiesPayloadOnInputAndOutput() {
        val source = byteArrayOf(4, 5)
        val response = AppleGattResponse(
            sessionId = SessionId,
            requestToken = AppleRequestToken(9),
            status = GattResponseStatus.Success,
            value = source,
        )

        source[0] = 99
        assertContentEquals(byteArrayOf(4, 5), response.value)
        response.value?.set(1, 88)
        assertContentEquals(byteArrayOf(4, 5), response.value)
    }

    @Test
    fun notificationRequestCopiesPayloadOnInputAndOutput() {
        val source = byteArrayOf(6, 7)
        val request = AppleNotificationRequest(
            sessionId = SessionId,
            characteristicId = CharacteristicId,
            mode = NotificationMode.Notification,
            value = source,
        )

        source[0] = 99
        assertContentEquals(byteArrayOf(6, 7), request.value)
        request.value[1] = 88
        assertContentEquals(byteArrayOf(6, 7), request.value)
    }

    private companion object {
        val SessionId = PeripheralSessionId("central")
        val ServiceId = GattServiceId("0000180d-0000-1000-8000-00805f9b34fb".toUuid())
        val CharacteristicId =
            GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val OtherCharacteristicId =
            GattCharacteristicId("00002a38-0000-1000-8000-00805f9b34fb".toUuid())
    }
}

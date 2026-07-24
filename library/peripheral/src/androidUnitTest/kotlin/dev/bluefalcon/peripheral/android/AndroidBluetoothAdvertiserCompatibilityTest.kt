package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.AdvertiserState
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorConfig
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class AndroidBluetoothAdvertiserCompatibilityTest {
    @Test
    fun advertisementOnlyConfigDoesNotOpenGattServer() = runTest {
        val stack = FakeAndroidBluetoothStack().apply {
            capabilities = AndroidStackCapabilities(
                localGattServer = false,
                connectableAdvertising = true,
            )
        }
        val advertiser = AndroidBluetoothAdvertiser(stack, NoOpLogger)

        advertiser.startAdvertising(AdvertiseConfig(localName = "advertisement-only"))

        assertEquals(AdvertiserState.Advertising, advertiser.state.value)
        assertFalse("open" in stack.calls)
        assertEquals(1, stack.calls.count { it == "advertise" })
    }

    @Test
    fun stopWaitsForStartupAndThenShutsBackendDown() = runTest {
        val startupGate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply {
            advertisingGate = startupGate
        }
        val advertiser = AndroidBluetoothAdvertiser(stack, NoOpLogger)

        val start = async { advertiser.startAdvertising(config()) }
        runCurrent()
        val stop = async { advertiser.stopAdvertising() }
        runCurrent()

        assertFalse(stop.isCompleted)
        startupGate.complete(Unit)
        start.await()
        stop.await()

        assertEquals(AdvertiserState.Idle, advertiser.state.value)
        assertEquals(1, stack.calls.count { it == "stopAdvertising" })
        assertEquals(1, stack.calls.count { it == "closeGattServer" })
    }

    @Test
    fun lifecycleUsesOneBackendOpenPerStartAndCanRestartAfterStop() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val advertiser = AndroidBluetoothAdvertiser(stack, NoOpLogger)

        assertEquals(AdvertiserState.Idle, advertiser.state.value)
        advertiser.startAdvertising(config())

        assertEquals(AdvertiserState.Advertising, advertiser.state.value)
        assertEquals(1, stack.calls.count { it == "open" })

        advertiser.stopAdvertising()

        assertEquals(AdvertiserState.Idle, advertiser.state.value)
        assertEquals(1, stack.calls.count { it == "stopAdvertising" })
        assertEquals(1, stack.calls.count { it == "closeGattServer" })

        advertiser.startAdvertising(config())

        assertEquals(AdvertiserState.Advertising, advertiser.state.value)
        assertEquals(2, stack.calls.count { it == "open" })
    }

    @Test
    fun automaticallyRespondsAndMaintainsOffsetAttributeValues() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val advertiser = AndroidBluetoothAdvertiser(stack, NoOpLogger)
        advertiser.startAdvertising(config())
        stack.emit(AndroidGattEvent.Connected(SessionOne))

        stack.emit(
            AndroidGattEvent.CharacteristicRead(
                sessionId = SessionOne,
                requestId = 10,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                offset = 1,
            ),
        )
        assertSuccessResponse(stack, requestId = 10, value = byteArrayOf(2, 3))

        stack.emit(
            AndroidGattEvent.DescriptorRead(
                sessionId = SessionOne,
                requestId = 11,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                descriptorId = DescriptorId,
                offset = 1,
            ),
        )
        assertSuccessResponse(stack, requestId = 11, value = byteArrayOf(5, 6))

        val emittedWrite = async { advertiser.characteristicWriteRequests.first() }
        runCurrent()
        val source = byteArrayOf(7, 8, 9)
        stack.emit(
            AndroidGattEvent.CharacteristicWrite(
                sessionId = SessionOne,
                requestId = 12,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                offset = 0,
                preparedWrite = false,
                responseNeeded = true,
                value = source,
            ),
        )
        source[0] = 99

        val legacyWrite = emittedWrite.await()
        assertEquals(ServiceUuid, legacyWrite.serviceUuid)
        assertEquals(CharacteristicUuid, legacyWrite.characteristicUuid)
        assertEquals(12, legacyWrite.requestId)
        assertContentEquals(byteArrayOf(7, 8, 9), legacyWrite.value)
        assertSuccessResponse(stack, requestId = 12, value = byteArrayOf(7, 8, 9))

        stack.emit(
            AndroidGattEvent.CharacteristicRead(
                sessionId = SessionOne,
                requestId = 13,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                offset = 1,
            ),
        )
        assertSuccessResponse(stack, requestId = 13, value = byteArrayOf(8, 9))

        stack.emit(
            AndroidGattEvent.DescriptorWrite(
                sessionId = SessionOne,
                requestId = 14,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                descriptorId = DescriptorId,
                offset = 0,
                preparedWrite = false,
                responseNeeded = true,
                value = byteArrayOf(20, 21, 22),
            ),
        )
        assertSuccessResponse(stack, requestId = 14, value = byteArrayOf(20, 21, 22))

        stack.emit(
            AndroidGattEvent.DescriptorRead(
                sessionId = SessionOne,
                requestId = 15,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                descriptorId = DescriptorId,
                offset = 2,
            ),
        )
        assertSuccessResponse(stack, requestId = 15, value = byteArrayOf(22))
    }

    @Test
    fun updateTargetsEveryActiveSubscribedSession() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val advertiser = AndroidBluetoothAdvertiser(stack, NoOpLogger)
        advertiser.startAdvertising(config())
        stack.emit(AndroidGattEvent.Connected(SessionOne))
        stack.emit(AndroidGattEvent.Connected(SessionTwo))
        stack.emit(AndroidGattEvent.Connected(UnsubscribedSession))
        stack.emitCccdSubscription(
            sessionId = SessionOne,
            requestId = 20,
            mode = NotificationMode.Notification,
        )
        stack.emitCccdSubscription(
            sessionId = SessionTwo,
            requestId = 21,
            mode = NotificationMode.Indication,
        )

        advertiser.updateCharacteristicValue(
            serviceUuid = ServiceUuid,
            characteristicUuid = CharacteristicUuid,
            value = byteArrayOf(42),
        )

        assertEquals(listOf(SessionOne, SessionTwo), stack.notifications.map { it.sessionId })
        assertEquals(
            listOf(NotificationMode.Notification, NotificationMode.Indication),
            stack.notifications.map { it.mode },
        )
        stack.notifications.forEach { notification ->
            assertEquals(CharacteristicId, notification.characteristicId)
            assertContentEquals(byteArrayOf(42), notification.value)
        }
    }

    private fun FakeAndroidBluetoothStack.emitCccdSubscription(
        sessionId: PeripheralSessionId,
        requestId: Int,
        mode: NotificationMode,
    ) {
        emit(
            AndroidGattEvent.DescriptorWrite(
                sessionId = sessionId,
                requestId = requestId,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                descriptorId = CccdId,
                offset = 0,
                preparedWrite = false,
                responseNeeded = false,
                value = when (mode) {
                    NotificationMode.Notification -> byteArrayOf(1, 0)
                    NotificationMode.Indication -> byteArrayOf(2, 0)
                },
            ),
        )
    }

    private fun assertSuccessResponse(
        stack: FakeAndroidBluetoothStack,
        requestId: Int,
        value: ByteArray,
    ) {
        val response = stack.responses.single { it.requestId == requestId }
        assertEquals(GattResponseStatus.Success, response.status)
        assertContentEquals(value, response.value)
    }

    private fun config() = AdvertiseConfig(
        serviceUuids = listOf(ServiceUuid),
        services = listOf(
            GattServiceConfig(
                uuid = ServiceUuid,
                characteristics = listOf(
                    GattCharacteristicConfig(
                        uuid = CharacteristicUuid,
                        properties = setOf(
                            CharacteristicProperty.READ,
                            CharacteristicProperty.WRITE,
                            CharacteristicProperty.NOTIFY,
                            CharacteristicProperty.INDICATE,
                        ),
                        initialValue = byteArrayOf(1, 2, 3),
                        descriptors = listOf(
                            GattDescriptorConfig(
                                uuid = DescriptorUuid,
                                initialValue = byteArrayOf(4, 5, 6),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private companion object {
        const val ServiceUuid = "00000000-0000-0000-0000-000000000101"
        const val CharacteristicUuid = "00000000-0000-0000-0000-000000000102"
        const val DescriptorUuid = "00000000-0000-0000-0000-000000000103"
        val ServiceId = GattServiceId(Uuid.parse(ServiceUuid))
        val CharacteristicId = GattCharacteristicId(Uuid.parse(CharacteristicUuid))
        val DescriptorId = GattDescriptorId(Uuid.parse(DescriptorUuid))
        val CccdId = GattDescriptorId(Uuid.parse("00002902-0000-1000-8000-00805f9b34fb"))
        val SessionOne = PeripheralSessionId("one")
        val SessionTwo = PeripheralSessionId("two")
        val UnsubscribedSession = PeripheralSessionId("unsubscribed")
    }
}

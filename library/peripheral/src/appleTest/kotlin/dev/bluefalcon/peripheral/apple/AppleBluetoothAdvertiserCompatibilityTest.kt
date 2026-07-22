package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.AdvertiserState
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class)
class AppleBluetoothAdvertiserCompatibilityTest {

    @Test
    fun stateReadSingleWriteAndRestartUseProductionRuntime() = runTest {
        val stack = FakeApplePeripheralStack()
        val advertiser = AppleBluetoothAdvertiser(stack, coroutineContext = coroutineContext)
        advertiser.startAdvertising(Config)

        assertEquals(AdvertiserState.Advertising, advertiser.state.value)
        stack.emit(readEvent(AppleRequestToken(1), offset = 1))
        runCurrent()
        assertContentEquals(byteArrayOf(2, 3), stack.responses.single().value)

        stack.responses.clear()
        val legacyWrite = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            advertiser.characteristicWriteRequests.take(1).toList().single()
        }
        stack.emit(
            AppleGattEvent.CharacteristicWrite(
                sessionId = SessionId,
                maximumUpdateValueLength = 20,
                requestToken = AppleRequestToken(2),
                write = AppleCharacteristicWrite(
                    ServiceId,
                    CharacteristicId,
                    offset = 1,
                    value = byteArrayOf(9),
                ),
            ),
        )
        runCurrent()
        assertContentEquals(byteArrayOf(9), legacyWrite.await().value)
        assertEquals(GattResponseStatus.Success, stack.responses.single().status)

        stack.responses.clear()
        stack.emit(readEvent(AppleRequestToken(3), offset = 0))
        runCurrent()
        assertContentEquals(byteArrayOf(1, 9, 3), stack.responses.single().value)

        advertiser.stopAdvertising()
        assertEquals(AdvertiserState.Idle, advertiser.state.value)
        advertiser.startAdvertising(Config)
        assertEquals(2, stack.openConfigs.size)
        assertEquals(1, stack.stopAdvertisingCalls)
        assertEquals(1, stack.clearServicesCalls)
        advertiser.stopAdvertising()
    }

    @Test
    fun batchWritesAreAtomicAndReceiveOneResponse() = runTest {
        val stack = FakeApplePeripheralStack()
        val advertiser = AppleBluetoothAdvertiser(stack, coroutineContext = coroutineContext)
        advertiser.startAdvertising(Config)
        val writes = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            advertiser.characteristicWriteRequests.take(2).toList()
        }

        stack.emit(
            AppleGattEvent.CharacteristicWriteBatch(
                sessionId = SessionId,
                maximumUpdateValueLength = 20,
                requestToken = AppleRequestToken(4),
                writes = listOf(
                    AppleCharacteristicWrite(
                        ServiceId,
                        CharacteristicId,
                        offset = 0,
                        value = byteArrayOf(7),
                    ),
                    AppleCharacteristicWrite(
                        ServiceId,
                        OtherCharacteristicId,
                        offset = 0,
                        value = byteArrayOf(8),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(2, writes.await().size)
        assertEquals(1, stack.responses.size)
        assertEquals(GattResponseStatus.Success, stack.responses.single().status)

        stack.responses.clear()
        stack.emit(
            AppleGattEvent.CharacteristicWriteBatch(
                sessionId = SessionId,
                maximumUpdateValueLength = 20,
                requestToken = AppleRequestToken(5),
                writes = listOf(
                    AppleCharacteristicWrite(
                        ServiceId,
                        CharacteristicId,
                        offset = 1,
                        value = byteArrayOf(6),
                    ),
                    AppleCharacteristicWrite(
                        ServiceId,
                        OtherCharacteristicId,
                        offset = 99,
                        value = byteArrayOf(5),
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals(GattResponseStatus.InvalidOffset, stack.responses.single().status)

        stack.responses.clear()
        stack.emit(readEvent(AppleRequestToken(6), offset = 0))
        runCurrent()
        assertContentEquals(byteArrayOf(7, 2, 3), stack.responses.single().value)
        advertiser.stopAdvertising()
    }

    @Test
    fun updateTargetsEverySubscribedSessionAndBusyRemainsBestEffort() = runTest {
        val stack = FakeApplePeripheralStack()
        val advertiser = AppleBluetoothAdvertiser(stack, coroutineContext = coroutineContext)
        advertiser.startAdvertising(Config)
        stack.emit(AppleGattEvent.Subscribed(SessionId, 20, CharacteristicId))
        stack.emit(AppleGattEvent.Subscribed(OtherSessionId, 12, CharacteristicId))
        runCurrent()

        advertiser.updateCharacteristicValue(
            ServiceUuid,
            CharacteristicUuid,
            byteArrayOf(4, 5),
        )

        assertEquals(setOf(SessionId, OtherSessionId), stack.notifications.map { it.sessionId }.toSet())
        stack.notificationResult = AppleNotificationStartResult.Busy
        advertiser.updateCharacteristicValue(
            ServiceUuid,
            CharacteristicUuid,
            byteArrayOf(6),
        )
        assertEquals(4, stack.notifications.size)
        advertiser.stopAdvertising()
    }

    private fun readEvent(token: AppleRequestToken, offset: Int) =
        AppleGattEvent.CharacteristicRead(
            sessionId = SessionId,
            maximumUpdateValueLength = 20,
            requestToken = token,
            serviceId = ServiceId,
            characteristicId = CharacteristicId,
            offset = offset,
        )

    private companion object {
        const val ServiceUuid = "0000180d-0000-1000-8000-00805f9b34fb"
        const val CharacteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb"
        const val OtherCharacteristicUuid = "00002a38-0000-1000-8000-00805f9b34fb"
        val ServiceId = GattServiceId(ServiceUuid.toUuid())
        val CharacteristicId = GattCharacteristicId(CharacteristicUuid.toUuid())
        val OtherCharacteristicId = GattCharacteristicId(OtherCharacteristicUuid.toUuid())
        val SessionId = PeripheralSessionId("central-1")
        val OtherSessionId = PeripheralSessionId("central-2")
        val Config = AdvertiseConfig(
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
                            ),
                            initialValue = byteArrayOf(1, 2, 3),
                        ),
                        GattCharacteristicConfig(
                            uuid = OtherCharacteristicUuid,
                            properties = setOf(CharacteristicProperty.WRITE),
                            initialValue = byteArrayOf(4),
                        ),
                    ),
                ),
            ),
        )
    }
}

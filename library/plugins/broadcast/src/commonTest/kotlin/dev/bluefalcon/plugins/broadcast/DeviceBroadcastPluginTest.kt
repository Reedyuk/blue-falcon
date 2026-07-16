package dev.bluefalcon.plugins.broadcast

import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.AdvertiserState
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.peripheral.CharacteristicWriteRequest
import dev.bluefalcon.plugins.clone.AdvertisementClone
import dev.bluefalcon.plugins.clone.DeviceClone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeviceBroadcastPluginTest {
    @Test
    fun startAndStopUseTheProvidedPeripheralAdvertiser() = runTest {
        val advertiser = RecordingAdvertiser()
        val plugin = DeviceBroadcastPlugin()
        val clone = DeviceClone(
            peripheralId = "device-id",
            peripheralName = "fallback-name",
            capturedAt = "2026-07-16T00:00:00Z",
            platform = "test",
            advertisement = AdvertisementClone(
                localName = "advertised-name",
                serviceUuids = listOf("service-uuid"),
            ),
        )

        plugin.startBroadcast(clone, advertiser)

        val config = assertNotNull(advertiser.startedWith)
        assertEquals("advertised-name", config.localName)
        assertEquals(listOf("service-uuid"), config.serviceUuids)

        plugin.stopBroadcast()

        assertEquals(1, advertiser.stopCalls)
    }

    private class RecordingAdvertiser : BluetoothAdvertiser {
        private val mutableState = MutableStateFlow(AdvertiserState.Idle)

        override val state: StateFlow<AdvertiserState> = mutableState
        override val characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest> =
            MutableSharedFlow()

        var startedWith: AdvertiseConfig? = null
        var stopCalls: Int = 0

        override suspend fun startAdvertising(config: AdvertiseConfig) {
            startedWith = config
            mutableState.value = AdvertiserState.Advertising
        }

        override suspend fun stopAdvertising() {
            stopCalls += 1
            mutableState.value = AdvertiserState.Idle
        }

        override suspend fun updateCharacteristicValue(
            serviceUuid: String,
            characteristicUuid: String,
            value: ByteArray,
        ) = Unit
    }
}

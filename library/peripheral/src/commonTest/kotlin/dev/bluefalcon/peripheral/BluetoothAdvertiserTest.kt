package dev.bluefalcon.peripheral

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BluetoothAdvertiserTest {
    @Test
    fun noOpAdvertiserStartsIdle() {
        assertEquals(AdvertiserState.Idle, NoOpBluetoothAdvertiser().state.value)
    }

    @Test
    fun noOpAdvertiserRejectsStart() = runTest {
        assertFailsWith<UnsupportedOperationException> {
            NoOpBluetoothAdvertiser().startAdvertising(AdvertiseConfig())
        }
    }

    @Test
    fun writeRequestUsesByteContentEquality() {
        assertEquals(
            CharacteristicWriteRequest("service", "characteristic", byteArrayOf(1, 2), 7),
            CharacteristicWriteRequest("service", "characteristic", byteArrayOf(1, 2), 7),
        )
    }
}

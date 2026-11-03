package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidLinkMtuMemoryTest {
    @Test fun rememberedMtuSeedsOnlyItsOwnDevice() {
        val memory = observingMemory()
        memory.remember(A, 293)
        memory.remember(A, 185)
        memory.remember(B, 517)
        assertEquals(185, memory.recall(A))
        assertEquals(517, memory.recall(B))
        assertNull(memory.recall(C))
        memory.onBroadcast(BluetoothDevice.ACTION_ACL_DISCONNECTED, B, BluetoothAdapter.ERROR)
        assertEquals(185, memory.recall(A))
        assertNull(memory.recall(B))
    }

    @Test fun nothingIsRememberedBeforeLinkDropsAreObserved() {
        val memory = AndroidLinkMtuMemory()
        memory.remember(A, 293)
        assertNull(memory.recall(A))
        memory.startObserving()
        assertNull(memory.recall(A))
        memory.remember(A, 293)
        assertEquals(293, memory.recall(A))
    }

    @Test fun aclDisconnectForgetsTheLinkUntilANewReport() {
        val memory = observingMemory()
        memory.remember(A, 293)
        memory.onBroadcast(BluetoothDevice.ACTION_ACL_DISCONNECTED, A, BluetoothAdapter.ERROR)
        assertNull(memory.recall(A))
        memory.remember(A, 26)
        assertEquals(26, memory.recall(A))
    }

    @Test fun aclDisconnectWithoutDeviceForgetsEveryLink() {
        val memory = observingMemory()
        memory.remember(A, 293)
        memory.remember(B, 517)
        memory.onBroadcast(BluetoothDevice.ACTION_ACL_DISCONNECTED, null, BluetoothAdapter.ERROR)
        assertNull(memory.recall(A))
        assertNull(memory.recall(B))
    }

    @Test fun adapterLeavingOnForgetsEveryLink() {
        listOf(
            BluetoothAdapter.STATE_TURNING_OFF,
            BluetoothAdapter.STATE_OFF,
            BluetoothAdapter.STATE_TURNING_ON,
            BluetoothAdapter.ERROR,
        ).forEach { state ->
            val memory = observingMemory()
            memory.remember(A, 293)
            memory.remember(B, 517)
            memory.onBroadcast(BluetoothAdapter.ACTION_STATE_CHANGED, null, state)
            assertNull(memory.recall(A), "state $state")
            assertNull(memory.recall(B), "state $state")
        }
    }

    @Test fun adapterOnAndUnrelatedBroadcastsKeepLinks() {
        val memory = observingMemory()
        memory.remember(A, 293)
        memory.onBroadcast(BluetoothAdapter.ACTION_STATE_CHANGED, null, BluetoothAdapter.STATE_ON)
        memory.onBroadcast(BluetoothDevice.ACTION_ACL_CONNECTED, A, BluetoothAdapter.ERROR)
        memory.onBroadcast(null, A, BluetoothAdapter.STATE_OFF)
        assertEquals(293, memory.recall(A))
    }

    @Test fun capacityEvictsTheOldestLink() {
        val memory = AndroidLinkMtuMemory(maximumEntries = 2).also { it.startObserving() }
        memory.remember(A, 30)
        memory.remember(B, 40)
        memory.remember(A, 31)
        memory.remember(C, 50)
        assertEquals(31, memory.recall(A))
        assertNull(memory.recall(B))
        assertEquals(50, memory.recall(C))
    }

    private fun observingMemory() = AndroidLinkMtuMemory().also { it.startObserving() }

    private companion object {
        const val A = "AA:AA:AA:AA:AA:AA"
        const val B = "BB:BB:BB:BB:BB:BB"
        const val C = "CC:CC:CC:CC:CC:CC"
    }
}

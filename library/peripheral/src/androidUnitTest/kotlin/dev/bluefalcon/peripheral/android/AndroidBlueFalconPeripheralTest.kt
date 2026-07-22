package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBlueFalconPeripheralTest {
    @Test
    fun internalFactoryBuildsCommonRuntimeWithAndroidCapabilities() {
        val peripheral: BlueFalconPeripheral = createBlueFalconPeripheral(
            stack = FakeAndroidBluetoothStack(),
            logger = NoOpLogger,
            coroutineContext = EmptyCoroutineContext,
        )

        assertTrue(peripheral.capabilities.localGattServer)
        assertTrue(peripheral.capabilities.targetedNotifications)
        assertFalse(peripheral.capabilities.stateRestoration)
    }
}

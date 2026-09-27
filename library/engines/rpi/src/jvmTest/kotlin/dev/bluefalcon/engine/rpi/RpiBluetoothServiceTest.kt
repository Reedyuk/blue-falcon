package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothGattCharacteristic
import com.welie.blessed.BluetoothGattService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Unit tests for [RpiBluetoothService]: every access to [RpiBluetoothService.characteristics] must
 * give the same wrappers, because a wrapper holds the characteristic's notification flow.
 */
class RpiBluetoothServiceTest {

    private val nativeService = BluetoothGattService(UUID.fromString(SERVICE)).apply {
        addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(CHARACTERISTIC), PROPERTIES))
    }

    @Test
    fun `characteristics gives the same wrapper on every access`() {
        val service = RpiBluetoothService(nativeService)

        assertSame(service.characteristics.single(), service.characteristics.single())
    }

    @Test
    fun `a notification delivered to the service's wrapper reaches a caller that collected earlier`() {
        runBlocking {
            val service = RpiBluetoothService(nativeService)
            val held = service.characteristics.single()
            val received = CompletableDeferred<ByteArray>()
            val collector = launch { received.complete(held.notifications.first()) }
            yield()

            // The engine looks the wrapper up again, as RpiEngine.onCharacteristicUpdate does.
            (service.characteristics.single() as RpiBluetoothCharacteristic).emitNotification(byteArrayOf(0x70, 0x01, 0x01))

            assertEquals(listOf<Byte>(0x70, 0x01, 0x01), withTimeout(1_000) { received.await() }.toList())
            collector.cancel()
        }
    }

    private companion object {
        const val SERVICE = "00001825-0000-1000-8000-00805f9b34fb"
        const val CHARACTERISTIC = "00002ac6-0000-1000-8000-00805f9b34fb"
        const val PROPERTIES = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE
    }
}

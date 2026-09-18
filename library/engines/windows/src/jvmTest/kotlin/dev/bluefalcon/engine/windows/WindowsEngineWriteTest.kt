package dev.bluefalcon.engine.windows

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WindowsEngineWriteTest {

    @Test
    fun `typed write uses Windows implementation instead of default unsupported result`() {
        runBlocking {
            val engine = WindowsEngine()

            val result = engine.writeCharacteristic(
                peripheral = FakePeripheral,
                characteristic = FakeCharacteristic,
                value = byteArrayOf(0x01),
                writeType = CharacteristicWriteType.WithResponse,
            )

            assertIs<CharacteristicWriteResult.Failed>(result)
        }
    }

    @Test
    fun `typed write reports disconnected for Windows peripherals without an active connection`() {
        runBlocking {
            val engine = WindowsEngine()

            val result = engine.writeCharacteristic(
                peripheral = WindowsBluetoothPeripheral(address = 0x112233445566, deviceName = null),
                characteristic = FakeCharacteristic,
                value = byteArrayOf(0x01),
                writeType = CharacteristicWriteType.WithoutResponse,
            )

            assertEquals(CharacteristicWriteResult.Disconnected, result)
        }
    }
}

private object FakePeripheral : BluetoothPeripheral {
    override val name: String? = "fake"
    override val uuid: String = "fake"
    override val rssi: Float? = null
    override val mtuSize: Int? = null
    override val services: List<BluetoothService> = emptyList()
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
}

private object FakeCharacteristic : BluetoothCharacteristic {
    override val uuid = "2A37".toUuid()
    override val name: String? = "fake"
    override val value: ByteArray? = null
    override val notifications = MutableSharedFlow<ByteArray>()
    override val descriptors: List<BluetoothCharacteristicDescriptor> = emptyList()
    override val isNotifying: Boolean = false
    override val service: BluetoothService? = null
}

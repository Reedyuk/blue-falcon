package dev.bluefalcon.engine.apple

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ApplePeripheralLifecycleTest {
    @Test
    fun `connection reuses scanned peripheral and preserves advertisement metadata`() {
        val manufacturerData = byteArrayOf(0x0E, 0x74, 0x98.toByte())
        val scanned = TestPeripheral(
            nativeReference = "scan-reference",
            manufacturerData = manufacturerData,
        )

        val connected = selectConnectionPeripheral(
            connected = null,
            scanned = scanned,
            create = { TestPeripheral("new-reference", byteArrayOf()) },
            updateNativePeripheral = { peripheral ->
                peripheral.nativeReference = "connection-reference"
            },
        )

        assertSame(scanned, connected)
        assertEquals("connection-reference", connected.nativeReference)
        assertContentEquals(manufacturerData, connected.manufacturerData)
    }

    @Test
    fun `reconnection prefers existing connected peripheral`() {
        val connected = TestPeripheral("old-connection", byteArrayOf(1))
        val scanned = TestPeripheral("scan-reference", byteArrayOf(2))

        val selected = selectConnectionPeripheral(
            connected = connected,
            scanned = scanned,
            create = { TestPeripheral("new-reference", byteArrayOf()) },
            updateNativePeripheral = { peripheral ->
                peripheral.nativeReference = "new-connection"
            },
        )

        assertSame(connected, selected)
        assertEquals("new-connection", selected.nativeReference)
        assertContentEquals(byteArrayOf(1), selected.manufacturerData)
    }
}

private data class TestPeripheral(
    var nativeReference: String,
    val manufacturerData: ByteArray,
)

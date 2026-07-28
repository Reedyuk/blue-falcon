package dev.bluefalcon.core

import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import dev.bluefalcon.core.mocks.FakeCharacteristic
import dev.bluefalcon.core.mocks.FakePeripheral
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CentralClientTypesTest {

    @Test
    fun `write key distinguishes peripheral and write type`() {
        val withoutResponse = CharacteristicWriteKey(
            peripheralUuid = "peer-a",
            writeType = CharacteristicWriteType.WithoutResponse,
        )

        assertEquals(
            withoutResponse,
            CharacteristicWriteKey("peer-a", CharacteristicWriteType.WithoutResponse),
        )
        kotlin.test.assertNotEquals(
            withoutResponse,
            CharacteristicWriteKey("peer-a", CharacteristicWriteType.WithResponse),
        )
        kotlin.test.assertNotEquals(
            withoutResponse,
            CharacteristicWriteKey("peer-b", CharacteristicWriteType.WithoutResponse),
        )
    }

    @Test
    fun `payload too large retains the authoritative maximum`() {
        assertEquals(
            244,
            CharacteristicWriteResult.PayloadTooLarge(maximumLength = 244).maximumLength,
        )
    }

    @Test
    fun `unsupported engine exposes stable empty capability state`() {
        val engine = FakeBlueFalconEngine()

        assertEquals(CentralCapabilities.None, engine.centralCapabilities)
        assertEquals(emptyMap(), engine.characteristicWriteCapabilities.value)
        assertSame(
            engine.characteristicWriteCapabilities,
            engine.characteristicWriteCapabilities,
        )
        assertNull(
            engine.maximumWriteValueLength(
                FakePeripheral(name = "peer"),
                CharacteristicWriteType.WithoutResponse,
            ),
        )
    }

    @Test
    fun `unsupported engine returns explicit typed outcomes`() = runTest {
        val engine = FakeBlueFalconEngine()
        val peripheral = FakePeripheral(name = "peer")
        val characteristic = FakeCharacteristic(
            uuid = "00000000-0000-0000-0000-000000000001".toUuid(),
        )

        assertEquals(
            CharacteristicWriteResult.Unsupported,
            engine.writeCharacteristic(
                peripheral,
                characteristic,
                byteArrayOf(1, 2, 3),
                CharacteristicWriteType.WithoutResponse,
            ),
        )
        assertEquals(
            NotificationSubscriptionResult.Unsupported,
            engine.setNotificationSubscription(
                peripheral,
                characteristic,
                enabled = true,
            ),
        )
    }
}

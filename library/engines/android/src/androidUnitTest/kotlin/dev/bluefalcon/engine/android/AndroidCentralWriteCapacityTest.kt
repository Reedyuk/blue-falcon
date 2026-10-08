package dev.bluefalcon.engine.android

import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCentralWriteCapacityTest {
    @Test fun mtu517Publishes512ForBothWriteModes() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        state.onMtuChanged("peer", generation, 517, true)
        CharacteristicWriteType.entries.forEach { mode ->
            assertEquals(512, state.capabilities.value.getValue(CharacteristicWriteKey("peer", mode)).maximumLength)
        }
    }

    @Test fun capacityBoundariesShrinkAndGrowWithoutOverflow() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val observations = listOf(23 to 20, 26 to 23, 293 to 290, 514 to 511,
            515 to 512, 517 to 512, Int.MAX_VALUE to 512, 26 to 23,
            3 to 0, 2 to 0, 0 to 0, -1 to 0, Int.MIN_VALUE to 0, 517 to 512)
        observations.forEach { (mtu, maximum) ->
            state.onMtuChanged("peer", generation, mtu, true)
            CharacteristicWriteType.entries.forEach { mode ->
                assertEquals(maximum, state.capabilities.value.getValue(CharacteristicWriteKey("peer", mode)).maximumLength, "MTU $mtu")
                assertNull(state.validateWrite("peer", generation, mode, maximum))
                assertEquals(CharacteristicWriteResult.PayloadTooLarge(maximum), state.validateWrite("peer", generation, mode, maximum + 1))
            }
        }
    }

    @Test fun busyToReadyRetainsBoundedCurrentCapacity() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        state.onBusy("peer", generation)
        state.onMtuChanged("peer", generation, 517, true)
        CharacteristicWriteType.entries.forEach { mode ->
            val key = CharacteristicWriteKey("peer", mode)
            assertFalse(state.capabilities.value.getValue(key).ready)
            assertEquals(512, state.capabilities.value.getValue(key).maximumLength)
        }
        state.onReady("peer", generation)
        CharacteristicWriteType.entries.forEach { mode ->
            val key = CharacteristicWriteKey("peer", mode)
            assertTrue(state.capabilities.value.getValue(key).ready)
            assertEquals(512, state.capabilities.value.getValue(key).maximumLength)
        }
    }

    @Test fun staleMtuCannotEnlargeReplacementOrDisconnectedOwner() {
        val state = AndroidCentralWriteState()
        val old = state.onConnected("peer")
        state.onMtuChanged("peer", old, 517, true)
        val current = state.onConnected("peer")
        val other = state.onConnected("other")
        state.onMtuChanged("other", other, 26, true)
        state.onMtuChanged("peer", old, 517, true)
        state.onReady("peer", old)
        state.onDisconnected("peer", old)
        CharacteristicWriteType.entries.forEach { mode ->
            assertEquals(20, state.capabilities.value.getValue(CharacteristicWriteKey("peer", mode)).maximumLength)
            assertEquals(23, state.capabilities.value.getValue(CharacteristicWriteKey("other", mode)).maximumLength)
            assertEquals(CharacteristicWriteResult.Disconnected, state.validateWrite("peer", old, mode, 1))
        }
        state.onDisconnected("peer", current)
        state.onMtuChanged("peer", current, 517, true)
        state.onReady("peer", current)
        assertTrue(state.capabilities.value.keys.none { it.peripheralUuid == "peer" })
    }
}

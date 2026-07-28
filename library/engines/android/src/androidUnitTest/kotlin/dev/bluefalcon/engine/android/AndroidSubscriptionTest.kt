package dev.bluefalcon.engine.android

import dev.bluefalcon.core.NotificationSubscriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSubscriptionTest {

    @Test
    fun `characteristic operation identity includes owning service`() {
        val characteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb"

        assertFalse(
            characteristicOperationIdentity("service-a", characteristicUuid) ==
                characteristicOperationIdentity("service-b", characteristicUuid)
        )
    }

    @Test
    fun `CCCD operation identity includes owning service and characteristic`() {
        val descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb"

        assertFalse(
            descriptorOperationIdentity("service-a", "characteristic", descriptorUuid) ==
                descriptorOperationIdentity("service-b", "characteristic", descriptorUuid)
        )
        assertFalse(
            descriptorOperationIdentity("service", "characteristic-a", descriptorUuid) ==
                descriptorOperationIdentity("service", "characteristic-b", descriptorUuid)
        )
    }

    @Test
    fun `typed target accepts only the exact native characteristic instance`() {
        val requested = Any()

        assertEquals(requested, exactNativeAttribute(requested, requested))
        assertEquals(null, exactNativeAttribute(requested, Any()))
        assertEquals(null, exactNativeAttribute(requested, null))
    }

    @Test
    fun `enable action writes notification CCCD only after local delivery succeeds`() {
        val localValues = mutableListOf<Boolean>()
        val descriptorValues = mutableListOf<ByteArray>()
        val action = AndroidNotificationSubscriptionAction(
            enabled = true,
            setLocalNotification = { enabled ->
                localValues += enabled
                true
            },
            writeCccd = { value ->
                descriptorValues += value.copyOf()
                true
            },
        )

        assertTrue(action.submit())
        assertEquals(listOf(true), localValues)
        assertTrue(
            descriptorValues.single().contentEquals(byteArrayOf(0x01, 0x00))
        )
    }

    @Test
    fun `disable action writes disabled CCCD value`() {
        var descriptorValue: ByteArray? = null
        val action = AndroidNotificationSubscriptionAction(
            enabled = false,
            setLocalNotification = { true },
            writeCccd = { value ->
                descriptorValue = value.copyOf()
                true
            },
        )

        assertTrue(action.submit())
        assertTrue(descriptorValue!!.contentEquals(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun `local notification rejection prevents descriptor write`() {
        var descriptorWriteCalled = false
        val action = AndroidNotificationSubscriptionAction(
            enabled = true,
            setLocalNotification = { false },
            writeCccd = {
                descriptorWriteCalled = true
                true
            },
        )

        assertFalse(action.submit())
        assertFalse(descriptorWriteCalled)
    }

    @Test
    fun `gate outcomes preserve subscription success failure and disconnect`() {
        assertEquals(
            NotificationSubscriptionResult.Updated(enabled = true),
            CentralGattOperationOutcome.Success(status = 0)
                .toSubscriptionResult(enabled = true),
        )
        assertTrue(
            CentralGattOperationOutcome.StatusFailure(status = 133)
                .toSubscriptionResult(enabled = true) is
                NotificationSubscriptionResult.Failed,
        )
        assertTrue(
            CentralGattOperationOutcome.Rejected(cause = null)
                .toSubscriptionResult(enabled = false) is
                NotificationSubscriptionResult.Failed,
        )
        assertTrue(
            CentralGattOperationOutcome.TimedOut
                .toSubscriptionResult(enabled = true) is
                NotificationSubscriptionResult.Failed,
        )
        assertEquals(
            NotificationSubscriptionResult.Disconnected,
            CentralGattOperationOutcome.Disconnected
                .toSubscriptionResult(enabled = true),
        )
    }
}

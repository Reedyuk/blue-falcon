package dev.bluefalcon.engine.android

import dev.bluefalcon.core.NotificationSubscriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSubscriptionTest {

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

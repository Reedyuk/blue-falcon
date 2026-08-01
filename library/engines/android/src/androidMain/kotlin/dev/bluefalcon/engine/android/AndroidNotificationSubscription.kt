package dev.bluefalcon.engine.android

import dev.bluefalcon.core.NotificationSubscriptionResult

internal fun characteristicOperationIdentity(
    serviceUuid: String?,
    characteristicUuid: String?,
): String = "${serviceUuid ?: "<unknown>"}/${characteristicUuid ?: "<unknown>"}"

internal fun descriptorOperationIdentity(
    serviceUuid: String?,
    characteristicUuid: String?,
    descriptorUuid: String?,
): String =
    "${characteristicOperationIdentity(serviceUuid, characteristicUuid)}/" +
        (descriptorUuid ?: "<unknown>")

internal fun <T : Any> exactNativeAttribute(requested: T, resolved: T?): T? =
    resolved?.takeIf { it === requested }

internal class AndroidNotificationSubscriptionAction(
    private val enabled: Boolean,
    private val setLocalNotification: (Boolean) -> Boolean,
    private val writeCccd: (ByteArray) -> Boolean,
) {
    fun submit(): Boolean {
        if (!setLocalNotification(enabled)) return false
        return writeCccd(
            if (enabled) {
                byteArrayOf(0x01, 0x00)
            } else {
                byteArrayOf(0x00, 0x00)
            }
        )
    }
}

internal fun CentralGattOperationOutcome.toSubscriptionResult(
    enabled: Boolean,
): NotificationSubscriptionResult = when (this) {
    is CentralGattOperationOutcome.Success ->
        NotificationSubscriptionResult.Updated(enabled)
    is CentralGattOperationOutcome.StatusFailure ->
        NotificationSubscriptionResult.Failed(
            IllegalStateException("GATT descriptor write failed with status $status")
        )
    is CentralGattOperationOutcome.Rejected ->
        NotificationSubscriptionResult.Failed(
            cause ?: IllegalStateException("Android rejected the notification subscription")
        )
    CentralGattOperationOutcome.TimedOut ->
        NotificationSubscriptionResult.Failed(
            IllegalStateException("Notification subscription timed out")
        )
    CentralGattOperationOutcome.Disconnected ->
        NotificationSubscriptionResult.Disconnected
}

package dev.bluefalcon.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class CharacteristicWriteType {
    WithResponse,
    WithoutResponse,
}

data class CharacteristicWriteKey(
    val peripheralUuid: String,
    val writeType: CharacteristicWriteType,
)

data class CharacteristicWriteCapability(
    val maximumLength: Int?,
    val ready: Boolean,
    val supported: Boolean,
)

data class CharacteristicWriteReady(
    val key: CharacteristicWriteKey,
)

sealed interface CharacteristicWriteResult {
    data object Sent : CharacteristicWriteResult

    data object Backpressured : CharacteristicWriteResult

    data class PayloadTooLarge(
        val maximumLength: Int,
    ) : CharacteristicWriteResult

    data object Disconnected : CharacteristicWriteResult

    data object Unsupported : CharacteristicWriteResult

    data class Failed(
        val cause: Throwable?,
    ) : CharacteristicWriteResult
}

sealed interface NotificationSubscriptionResult {
    data class Updated(
        val enabled: Boolean,
    ) : NotificationSubscriptionResult

    data object Disconnected : NotificationSubscriptionResult

    data object Unsupported : NotificationSubscriptionResult

    data class Failed(
        val cause: Throwable?,
    ) : NotificationSubscriptionResult
}

data class NotificationSubscriptionUpdate(
    val peripheralUuid: String,
    val characteristicUuid: Uuid,
    val result: NotificationSubscriptionResult,
)

/**
 * Describes the platform's bonding/pairing capability.
 */
enum class BondCapability {
    /** The platform can both request bonding and report state changes (Android, RPi/BlueZ). */
    Supported,
    /** Bonding happens implicitly on first encrypted access; the app cannot request or observe it (Apple). */
    Implicit,
    /** The platform exposes no bonding API at all (Windows, JS/Web Bluetooth). */
    Unsupported,
}

data class CentralCapabilities(
    val reliableWriteResults: Boolean,
    val writeWithoutResponseReadiness: Boolean,
    val perConnectionMaximumWriteLength: Boolean,
    val notificationSubscriptionResults: Boolean,
    val restoration: Boolean,
    val bondCapability: BondCapability = BondCapability.Unsupported,
) {
    companion object {
        val None = CentralCapabilities(
            reliableWriteResults = false,
            writeWithoutResponseReadiness = false,
            perConnectionMaximumWriteLength = false,
            notificationSubscriptionResults = false,
            restoration = false,
            bondCapability = BondCapability.Unsupported,
        )
    }
}

internal val EmptyCharacteristicWriteCapabilities:
    StateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>> =
    MutableStateFlow(emptyMap())

internal val EmptyCharacteristicWriteReady: SharedFlow<CharacteristicWriteReady> =
    MutableSharedFlow()

internal val EmptyNotificationSubscriptionUpdates: SharedFlow<NotificationSubscriptionUpdate> =
    MutableSharedFlow()

internal val EmptyBondStateUpdates: SharedFlow<BondStateUpdate> =
    MutableSharedFlow()

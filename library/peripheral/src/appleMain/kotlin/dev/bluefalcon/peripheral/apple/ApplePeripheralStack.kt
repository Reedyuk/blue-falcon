package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlin.jvm.JvmInline

internal interface ApplePeripheralStack {
    suspend fun open(
        config: PeripheralConfig,
        listener: ApplePeripheralStackListener,
    ): AppleOpenResult

    fun sendResponse(response: AppleGattResponse): Boolean

    fun notify(request: AppleNotificationRequest): AppleNotificationStartResult

    fun stopAdvertising()

    fun clearServices()

    fun close()
}

internal interface ApplePeripheralStackListener {
    fun onEvent(event: AppleGattEvent)

    fun onPlatformFailure(cause: Throwable)
}

internal class AppleOpenResult(
    val restored: Boolean,
    val advertising: Boolean,
    restoredSessions: List<AppleRestoredSession> = emptyList(),
) {
    private val copiedRestoredSessions = restoredSessions.map { session -> session.copyForBoundary() }

    val restoredSessions: List<AppleRestoredSession>
        get() = copiedRestoredSessions.map { session -> session.copyForBoundary() }
}

internal class AppleRestoredSession(
    val sessionId: PeripheralSessionId,
    val maximumUpdateValueLength: Int,
    subscriptions: Set<GattCharacteristicId>,
) {
    private val copiedSubscriptions = subscriptions.toSet()

    val subscriptions: Set<GattCharacteristicId>
        get() = copiedSubscriptions.toSet()

    fun copyForBoundary() = AppleRestoredSession(
        sessionId = sessionId,
        maximumUpdateValueLength = maximumUpdateValueLength,
        subscriptions = subscriptions,
    )
}

@JvmInline
internal value class AppleRequestToken(val value: Long)

internal sealed interface AppleGattEvent {
    class CharacteristicRead(
        val sessionId: PeripheralSessionId,
        val maximumUpdateValueLength: Int,
        val requestToken: AppleRequestToken,
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val offset: Int,
    ) : AppleGattEvent

    class CharacteristicWrite(
        val sessionId: PeripheralSessionId,
        val maximumUpdateValueLength: Int,
        val requestToken: AppleRequestToken,
        val write: AppleCharacteristicWrite,
    ) : AppleGattEvent {
        val copiedWrite: AppleCharacteristicWrite
            get() = write.copyForBoundary()
    }

    class CharacteristicWriteBatch(
        val sessionId: PeripheralSessionId,
        val maximumUpdateValueLength: Int,
        val requestToken: AppleRequestToken,
        writes: List<AppleCharacteristicWrite>,
    ) : AppleGattEvent {
        private val copiedWrites = writes.map { write -> write.copyForBoundary() }

        val writes: List<AppleCharacteristicWrite>
            get() = copiedWrites.map { write -> write.copyForBoundary() }

        init {
            require(copiedWrites.isNotEmpty()) {
                "Apple characteristic write batch must not be empty"
            }
        }
    }

    class Subscribed(
        val sessionId: PeripheralSessionId,
        val maximumUpdateValueLength: Int,
        val characteristicId: GattCharacteristicId,
    ) : AppleGattEvent

    class Unsubscribed(
        val sessionId: PeripheralSessionId,
        val maximumUpdateValueLength: Int,
        val characteristicId: GattCharacteristicId,
    ) : AppleGattEvent

    data object NotificationReady : AppleGattEvent
}

internal class AppleCharacteristicWrite(
    val serviceId: GattServiceId,
    val characteristicId: GattCharacteristicId,
    val offset: Int,
    value: ByteArray,
) {
    private val copiedValue = value.copyOf()

    val value: ByteArray
        get() = copiedValue.copyOf()

    fun copyForBoundary() = AppleCharacteristicWrite(
        serviceId = serviceId,
        characteristicId = characteristicId,
        offset = offset,
        value = value,
    )
}

internal class AppleGattResponse(
    val sessionId: PeripheralSessionId,
    val requestToken: AppleRequestToken,
    val status: GattResponseStatus,
    value: ByteArray?,
) {
    private val copiedValue = value?.copyOf()

    val value: ByteArray?
        get() = copiedValue?.copyOf()
}

internal class AppleNotificationRequest(
    val sessionId: PeripheralSessionId,
    val characteristicId: GattCharacteristicId,
    val mode: NotificationMode,
    value: ByteArray,
) {
    private val copiedValue = value.copyOf()

    val value: ByteArray
        get() = copiedValue.copyOf()
}

internal sealed interface AppleNotificationStartResult {
    data object Accepted : AppleNotificationStartResult
    data object Busy : AppleNotificationStartResult
    data object Disconnected : AppleNotificationStartResult
    data class Rejected(val cause: Throwable) : AppleNotificationStartResult
}

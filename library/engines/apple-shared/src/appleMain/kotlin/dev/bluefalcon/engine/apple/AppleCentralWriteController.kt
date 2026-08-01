package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.CharacteristicWriteCapability
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteReady
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.NotificationSubscriptionResult
import dev.bluefalcon.core.NotificationSubscriptionUpdate
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun appleCharacteristicIdentity(
    serviceUuid: String?,
    characteristicUuid: String,
): String = "${serviceUuid ?: "<unknown>"}/$characteristicUuid"

internal fun <T : Any> nativeAttributeBelongsTo(
    expectedOwner: T,
    actualOwner: T?,
): Boolean = expectedOwner === actualOwner

internal interface AppleCentralWritePeer {
    val peripheralUuid: String
    val connected: Boolean
    val canSendWithoutResponse: Boolean

    fun maximumWriteValueLength(writeType: CharacteristicWriteType): Int
}

internal interface AppleCentralWriteTarget : AppleCentralWritePeer {
    val characteristicUuid: String

    fun writeValue(
        payload: ByteArray,
        writeType: CharacteristicWriteType,
    )
}

internal interface AppleNotificationTarget {
    val peripheralUuid: String
    val characteristicIdentity: String
    val characteristicUuid: Uuid
    val connected: Boolean

    suspend fun setNotifyValue(enabled: Boolean)
}

internal class AppleCentralWriteController(
    scope: CoroutineScope,
    internal val registry: AppleCentralOperationRegistry = AppleCentralOperationRegistry(),
) {
    private val mutex = Mutex()
    private val connections = mutableMapOf<String, AppleCentralConnectionKey>()

    private val _capabilities =
        MutableStateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>>(emptyMap())
    val capabilities: StateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>> =
        _capabilities.asStateFlow()

    private val _ready = MutableSharedFlow<CharacteristicWriteReady>(extraBufferCapacity = 64)
    val ready: SharedFlow<CharacteristicWriteReady> = _ready.asSharedFlow()

    private val _notificationUpdates =
        MutableSharedFlow<NotificationSubscriptionUpdate>(extraBufferCapacity = 64)
    val notificationUpdates: SharedFlow<NotificationSubscriptionUpdate> =
        _notificationUpdates.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            registry.readyEdges.collect { connection ->
                val active = mutex.withLock {
                    connections[connection.peripheralUuid] == connection
                }
                if (active) {
                    _ready.tryEmit(
                        CharacteristicWriteReady(
                            CharacteristicWriteKey(
                                connection.peripheralUuid,
                                CharacteristicWriteType.WithoutResponse,
                            )
                        )
                    )
                }
            }
        }
    }

    suspend fun connected(peer: AppleCentralWritePeer): AppleCentralConnectionKey {
        val maximumWithResponse =
            peer.maximumWriteValueLength(CharacteristicWriteType.WithResponse)
        val maximumWithoutResponse =
            peer.maximumWriteValueLength(CharacteristicWriteType.WithoutResponse)
        val connection = registry.connected(peer.peripheralUuid)
        mutex.withLock {
            connections[peer.peripheralUuid] = connection
            _capabilities.value = _capabilities.value
                .filterKeys { it.peripheralUuid != peer.peripheralUuid } +
                mapOf(
                    CharacteristicWriteKey(
                        peer.peripheralUuid,
                        CharacteristicWriteType.WithResponse,
                    ) to CharacteristicWriteCapability(
                        maximumLength = maximumWithResponse,
                        ready = true,
                        supported = true,
                    ),
                    CharacteristicWriteKey(
                        peer.peripheralUuid,
                        CharacteristicWriteType.WithoutResponse,
                    ) to CharacteristicWriteCapability(
                        maximumLength = maximumWithoutResponse,
                        ready = peer.canSendWithoutResponse,
                        supported = true,
                    ),
                )
        }
        return connection
    }

    suspend fun disconnected(peripheralUuid: String): Boolean {
        val connection = currentConnection(peripheralUuid) ?: return false
        return disconnected(connection)
    }

    suspend fun disconnected(connection: AppleCentralConnectionKey): Boolean {
        val removedConnection = mutex.withLock {
            if (connections[connection.peripheralUuid] != connection) return false
            val removed = connections.remove(connection.peripheralUuid) ?: return false
            _capabilities.value =
                _capabilities.value.filterKeys {
                    it.peripheralUuid != connection.peripheralUuid
                }
            removed
        }
        return registry.disconnect(removedConnection)
    }

    suspend fun write(
        target: AppleCentralWriteTarget,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        if (!target.connected) return CharacteristicWriteResult.Disconnected
        val connection = currentConnection(target.peripheralUuid)
            ?: return CharacteristicWriteResult.Disconnected
        val capability = _capabilities.value[
            CharacteristicWriteKey(target.peripheralUuid, writeType)
        ] ?: return CharacteristicWriteResult.Disconnected
        if (!capability.supported) return CharacteristicWriteResult.Unsupported
        capability.maximumLength?.let { maximumLength ->
            if (value.size > maximumLength) {
                return CharacteristicWriteResult.PayloadTooLarge(maximumLength)
            }
        }

        val payload = value.copyOf()
        return when (writeType) {
            CharacteristicWriteType.WithoutResponse ->
                writeWithoutResponse(connection, target, payload)
            CharacteristicWriteType.WithResponse ->
                writeWithResponse(connection, target, payload)
        }
    }

    suspend fun onReadyToSendWithoutResponse(peer: AppleCentralWritePeer): Boolean {
        val connection = currentConnection(peer.peripheralUuid) ?: return false
        return onReadyToSendWithoutResponse(connection, peer)
    }

    suspend fun onReadyToSendWithoutResponse(
        connection: AppleCentralConnectionKey,
        peer: AppleCentralWritePeer,
    ): Boolean {
        if (connection.peripheralUuid != peer.peripheralUuid ||
            currentConnection(peer.peripheralUuid) != connection
        ) {
            return false
        }
        updateCapabilityReady(
            peer.peripheralUuid,
            CharacteristicWriteType.WithoutResponse,
            peer.canSendWithoutResponse,
        )
        return registry.updateReadiness(connection, peer.canSendWithoutResponse)
    }

    suspend fun onCharacteristicWritten(
        peripheralUuid: String,
        characteristicUuid: String,
        failure: Throwable?,
    ): Boolean {
        val connection = currentConnection(peripheralUuid) ?: return false
        return onCharacteristicWritten(connection, characteristicUuid, failure)
    }

    suspend fun onCharacteristicWritten(
        connection: AppleCentralConnectionKey,
        characteristicUuid: String,
        failure: Throwable?,
    ): Boolean {
        val key = AppleCentralOperationKey(
            peripheralUuid = connection.peripheralUuid,
            generation = connection.generation,
            characteristicUuid = characteristicUuid,
        )
        val completed = registry.completeWrite(
            key,
            failure?.let(CharacteristicWriteResult::Failed)
                ?: CharacteristicWriteResult.Sent,
        )
        if (completed) {
            updateCapabilityReady(
                connection.peripheralUuid,
                CharacteristicWriteType.WithResponse,
                ready = true,
            )
        }
        return completed
    }

    suspend fun setNotificationSubscription(
        target: AppleNotificationTarget,
        enabled: Boolean,
    ): NotificationSubscriptionResult {
        fun report(result: NotificationSubscriptionResult): NotificationSubscriptionResult {
            return reportNotificationUpdate(
                peripheralUuid = target.peripheralUuid,
                characteristicUuid = target.characteristicUuid,
                result = result,
            )
        }

        if (!target.connected) {
            return report(NotificationSubscriptionResult.Disconnected)
        }
        val connection = currentConnection(target.peripheralUuid)
            ?: return report(NotificationSubscriptionResult.Disconnected)
        val key = AppleCentralOperationKey(
            peripheralUuid = target.peripheralUuid,
            generation = connection.generation,
            characteristicUuid = target.characteristicIdentity,
        )
        val result = CompletableDeferred<NotificationSubscriptionResult>()
        if (!registry.registerSubscription(key, enabled) { outcome ->
                result.complete(report(outcome))
            }
        ) {
            return report(
                NotificationSubscriptionResult.Failed(
                    IllegalStateException(
                        "A notification subscription is already pending for this characteristic"
                    )
                )
            )
        }
        try {
            target.setNotifyValue(enabled)
        } catch (cancellation: CancellationException) {
            registry.abandonSubscription(key)
            throw cancellation
        } catch (failure: Throwable) {
            registry.completeSubscription(
                key,
                NotificationSubscriptionResult.Failed(failure),
            )
        }

        return try {
            result.await()
        } catch (cancellation: CancellationException) {
            registry.abandonSubscription(key)
            throw cancellation
        }
    }

    suspend fun onNotificationStateUpdated(
        peripheralUuid: String,
        characteristicIdentity: String,
        isNotifying: Boolean,
        failure: Throwable?,
    ): Boolean {
        val connection = currentConnection(peripheralUuid) ?: return false
        return onNotificationStateUpdated(
            connection,
            characteristicIdentity,
            isNotifying,
            failure,
        )
    }

    suspend fun onNotificationStateUpdated(
        connection: AppleCentralConnectionKey,
        characteristicIdentity: String,
        isNotifying: Boolean,
        failure: Throwable?,
    ): Boolean {
        val key = AppleCentralOperationKey(
            peripheralUuid = connection.peripheralUuid,
            generation = connection.generation,
            characteristicUuid = characteristicIdentity,
        )
        val expectedState = registry.subscriptionTarget(key) ?: return false
        val result = when {
            failure != null -> NotificationSubscriptionResult.Failed(failure)
            isNotifying != expectedState -> NotificationSubscriptionResult.Failed(
                IllegalStateException(
                    "CoreBluetooth reported isNotifying=$isNotifying, expected $expectedState"
                )
            )
            else -> NotificationSubscriptionResult.Updated(expectedState)
        }
        return registry.completeSubscription(key, result)
    }

    internal suspend fun pendingSubscriptionTarget(
        peripheralUuid: String,
        characteristicIdentity: String,
    ): Boolean? {
        val connection = currentConnection(peripheralUuid) ?: return null
        return registry.subscriptionTarget(
            AppleCentralOperationKey(
                peripheralUuid = peripheralUuid,
                generation = connection.generation,
                characteristicUuid = characteristicIdentity,
            )
        )
    }

    internal fun reportNotificationUpdate(
        peripheralUuid: String,
        characteristicUuid: Uuid,
        result: NotificationSubscriptionResult,
    ): NotificationSubscriptionResult {
        _notificationUpdates.tryEmit(
            NotificationSubscriptionUpdate(
                peripheralUuid = peripheralUuid,
                characteristicUuid = characteristicUuid,
                result = result,
            )
        )
        return result
    }

    internal suspend fun currentConnection(
        peripheralUuid: String,
    ): AppleCentralConnectionKey? = mutex.withLock {
        connections[peripheralUuid]
    }

    private suspend fun writeWithoutResponse(
        connection: AppleCentralConnectionKey,
        target: AppleCentralWriteTarget,
        payload: ByteArray,
    ): CharacteristicWriteResult {
        if (!target.canSendWithoutResponse) {
            updateCapabilityReady(
                target.peripheralUuid,
                CharacteristicWriteType.WithoutResponse,
                ready = false,
            )
            registry.updateReadiness(connection, ready = false)
            return CharacteristicWriteResult.Backpressured
        }
        return try {
            target.writeValue(payload, CharacteristicWriteType.WithoutResponse)
            CharacteristicWriteResult.Sent
        } catch (failure: Throwable) {
            CharacteristicWriteResult.Failed(failure)
        }
    }

    private suspend fun writeWithResponse(
        connection: AppleCentralConnectionKey,
        target: AppleCentralWriteTarget,
        payload: ByteArray,
    ): CharacteristicWriteResult {
        val key = AppleCentralOperationKey(
            peripheralUuid = target.peripheralUuid,
            generation = connection.generation,
            characteristicUuid = target.characteristicUuid,
        )
        val result = CompletableDeferred<CharacteristicWriteResult>()
        if (!registry.registerWrite(key, result::complete)) {
            return CharacteristicWriteResult.Backpressured
        }
        updateCapabilityReady(
            target.peripheralUuid,
            CharacteristicWriteType.WithResponse,
            ready = false,
        )
        try {
            target.writeValue(payload, CharacteristicWriteType.WithResponse)
        } catch (failure: Throwable) {
            registry.completeWrite(key, CharacteristicWriteResult.Failed(failure))
            updateCapabilityReady(
                target.peripheralUuid,
                CharacteristicWriteType.WithResponse,
                ready = true,
            )
        }

        return try {
            result.await()
        } catch (cancellation: CancellationException) {
            registry.abandonWrite(key)
            throw cancellation
        }
    }

    private suspend fun updateCapabilityReady(
        peripheralUuid: String,
        writeType: CharacteristicWriteType,
        ready: Boolean,
    ) {
        mutex.withLock {
            val key = CharacteristicWriteKey(peripheralUuid, writeType)
            val existing = _capabilities.value[key] ?: return
            _capabilities.value = _capabilities.value + (key to existing.copy(ready = ready))
        }
    }
}

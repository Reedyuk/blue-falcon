package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.NotificationSubscriptionResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AppleCentralConnectionKey(
    val peripheralUuid: String,
    val generation: Long,
)

internal data class AppleCentralOperationKey(
    val peripheralUuid: String,
    val generation: Long,
    val characteristicUuid: String,
) {
    val connection: AppleCentralConnectionKey
        get() = AppleCentralConnectionKey(peripheralUuid, generation)
}

internal class AppleCentralOperationRegistry {
    private val mutex = Mutex()
    private val lastGenerations = mutableMapOf<String, Long>()
    private val activeConnections = mutableMapOf<String, AppleCentralConnectionKey>()
    private val writes = mutableMapOf<AppleCentralConnectionKey, PendingWrite>()
    private val subscriptions = mutableMapOf<AppleCentralOperationKey, PendingSubscription>()

    private val _readiness =
        MutableStateFlow<Map<AppleCentralConnectionKey, Boolean>>(emptyMap())
    val readiness: StateFlow<Map<AppleCentralConnectionKey, Boolean>> =
        _readiness.asStateFlow()

    private val _readyEdges =
        MutableSharedFlow<AppleCentralConnectionKey>(extraBufferCapacity = 64)
    val readyEdges: SharedFlow<AppleCentralConnectionKey> = _readyEdges.asSharedFlow()

    suspend fun connected(peripheralUuid: String): AppleCentralConnectionKey {
        val transition = mutex.withLock {
            val completions = activeConnections[peripheralUuid]
                ?.let(::removeConnectionLocked)
                .orEmpty()
            val generation = (lastGenerations[peripheralUuid] ?: 0L) + 1L
            lastGenerations[peripheralUuid] = generation
            val connection = AppleCentralConnectionKey(peripheralUuid, generation)
            activeConnections[peripheralUuid] = connection
            _readiness.value = _readiness.value
                .filterKeys { it.peripheralUuid != peripheralUuid } + (connection to false)
            ConnectionTransition(connection, completions)
        }
        transition.completions.forEach { it() }
        return transition.connection
    }

    suspend fun registerWrite(
        key: AppleCentralOperationKey,
        onComplete: (CharacteristicWriteResult) -> Unit,
    ): Boolean = mutex.withLock {
        if (!isActiveLocked(key.connection) || writes.containsKey(key.connection)) {
            return@withLock false
        }
        writes[key.connection] = PendingWrite(key, onComplete)
        true
    }

    suspend fun completeWrite(
        key: AppleCentralOperationKey,
        result: CharacteristicWriteResult,
    ): Boolean {
        val completion = mutex.withLock {
            if (!isActiveLocked(key.connection)) return false
            val pending = writes[key.connection] ?: return false
            if (pending.key != key) return false
            writes.remove(key.connection)
            pending.onComplete
        }
        completion?.invoke(result)
        return true
    }

    suspend fun abandonWrite(key: AppleCentralOperationKey): Boolean =
        mutex.withLock {
            val pending = writes[key.connection] ?: return@withLock false
            if (pending.key != key) return@withLock false
            pending.onComplete = null
            true
        }

    suspend fun registerSubscription(
        key: AppleCentralOperationKey,
        enabled: Boolean,
        onComplete: (NotificationSubscriptionResult) -> Unit,
    ): Boolean = mutex.withLock {
        if (!isActiveLocked(key.connection) || subscriptions.containsKey(key)) {
            return@withLock false
        }
        subscriptions[key] = PendingSubscription(
            enabled = enabled,
            onComplete = onComplete,
        )
        true
    }

    suspend fun completeSubscription(
        key: AppleCentralOperationKey,
        result: NotificationSubscriptionResult,
    ): Boolean {
        val completion = mutex.withLock {
            if (!isActiveLocked(key.connection)) return false
            val pending = subscriptions.remove(key) ?: return false
            pending.onComplete
        }
        completion?.invoke(result)
        return true
    }

    suspend fun subscriptionTarget(key: AppleCentralOperationKey): Boolean? =
        mutex.withLock {
            if (isActiveLocked(key.connection)) subscriptions[key]?.enabled else null
        }

    suspend fun abandonSubscription(key: AppleCentralOperationKey): Boolean =
        mutex.withLock {
            val pending = subscriptions[key] ?: return@withLock false
            pending.onComplete = null
            true
        }

    suspend fun disconnect(connection: AppleCentralConnectionKey): Boolean {
        val completions = mutex.withLock {
            if (!isActiveLocked(connection)) return false
            removeConnectionLocked(connection)
        }
        completions.forEach { it() }
        return true
    }

    suspend fun updateReadiness(
        connection: AppleCentralConnectionKey,
        ready: Boolean,
    ): Boolean {
        val emitEdge = mutex.withLock {
            if (!isActiveLocked(connection)) return false
            val previous = _readiness.value[connection] ?: false
            _readiness.value = _readiness.value + (connection to ready)
            !previous && ready
        }
        if (emitEdge) {
            _readyEdges.tryEmit(connection)
        }
        return true
    }

    private fun isActiveLocked(connection: AppleCentralConnectionKey): Boolean =
        activeConnections[connection.peripheralUuid] == connection

    private fun removeConnectionLocked(
        connection: AppleCentralConnectionKey,
    ): List<() -> Unit> {
        activeConnections.remove(connection.peripheralUuid)
        _readiness.value = _readiness.value - connection

        val callbacks = mutableListOf<() -> Unit>()
        writes.remove(connection)?.onComplete?.let { completion ->
            callbacks += { completion(CharacteristicWriteResult.Disconnected) }
        }
        subscriptions.keys
            .filter { it.connection == connection }
            .forEach { key ->
                subscriptions.remove(key)?.onComplete?.let { completion ->
                    callbacks += {
                        completion(NotificationSubscriptionResult.Disconnected)
                    }
                }
            }
        return callbacks
    }

    private data class ConnectionTransition(
        val connection: AppleCentralConnectionKey,
        val completions: List<() -> Unit>,
    )

    private data class PendingWrite(
        val key: AppleCentralOperationKey,
        var onComplete: ((CharacteristicWriteResult) -> Unit)?,
    )

    private data class PendingSubscription(
        val enabled: Boolean,
        var onComplete: ((NotificationSubscriptionResult) -> Unit)?,
    )
}

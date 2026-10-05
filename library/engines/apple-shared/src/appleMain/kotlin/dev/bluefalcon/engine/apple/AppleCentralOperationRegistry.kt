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

/**
 * Outcome of a pending characteristic read (ADR 0014), resolved from
 * `didUpdateValueForCharacteristic` once it is correlated to a specific pending
 * [AppleCentralOperationRegistry.registerRead] request rather than an unrelated notification.
 */
internal sealed interface AppleReadOutcome {
    data class Success(val value: ByteArray?) : AppleReadOutcome
    data object Disconnected : AppleReadOutcome
    data class Failed(val cause: Throwable) : AppleReadOutcome
}

internal class AppleCentralOperationRegistry(
    private val maximumPeers: Int = 32,
    private val maximumAttributes: Int = 256,
) {
    private val mutex = Mutex()
    // Registry-wide sequence preserves ABA fencing without retaining departed peer IDs.
    private var nextGeneration = 0L
    private val activeConnections = mutableMapOf<String, AppleCentralConnectionKey>()
    private val writes = mutableMapOf<AppleCentralConnectionKey, PendingWrite>()
    private val subscriptions = mutableMapOf<AppleCentralOperationKey, PendingSubscription>()
    private val reads = mutableMapOf<AppleCentralOperationKey, PendingRead>()
    private val quarantined = mutableSetOf<AppleCentralConnectionKey>()
    private val notifying = mutableSetOf<AppleCentralOperationKey>()

    /** Counts retained peer identities, including inactive history, for resource diagnostics. */
    internal suspend fun retainedPeerCount(): Int = mutex.withLock {
        activeConnections.size
    }

    private val _readiness =
        MutableStateFlow<Map<AppleCentralConnectionKey, Boolean>>(emptyMap())
    val readiness: StateFlow<Map<AppleCentralConnectionKey, Boolean>> =
        _readiness.asStateFlow()

    private val _readyEdges =
        MutableSharedFlow<AppleCentralConnectionKey>(extraBufferCapacity = 64)
    val readyEdges: SharedFlow<AppleCentralConnectionKey> = _readyEdges.asSharedFlow()

    suspend fun connected(peripheralUuid: String): AppleCentralConnectionKey {
        val transition = mutex.withLock {
            check(peripheralUuid in activeConnections || activeConnections.size < maximumPeers) {
                "Apple central peer capacity reached ($maximumPeers)"
            }
            check(nextGeneration < Long.MAX_VALUE) { "Apple central generation capacity exhausted" }
            val completions = activeConnections[peripheralUuid]
                ?.let(::removeConnectionLocked)
                .orEmpty()
            val generation = ++nextGeneration
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
        if (!isActiveLocked(key.connection) || subscriptions.containsKey(key) || reads.containsKey(key) ||
            !canRetainAttributeLocked(key)
        ) {
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
            if (result is NotificationSubscriptionResult.Updated) {
                if (result.enabled) notifying.add(key) else notifying.remove(key)
            }
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

    suspend fun registerRead(
        key: AppleCentralOperationKey,
        onComplete: (AppleReadOutcome) -> Unit,
    ): Boolean = mutex.withLock {
        // CoreBluetooth does not distinguish a read response from a notification.
        if (!isActiveLocked(key.connection) || reads.containsKey(key) ||
            key in notifying || subscriptions.containsKey(key) || !canRetainAttributeLocked(key)
        ) {
            return@withLock false
        }
        reads[key] = PendingRead(onComplete)
        true
    }

    suspend fun completeRead(
        key: AppleCentralOperationKey,
        outcome: AppleReadOutcome,
    ): Boolean {
        val completion = mutex.withLock {
            if (!isActiveLocked(key.connection)) return false
            val pending = reads.remove(key) ?: return false
            pending.onComplete
        }
        completion?.invoke(outcome)
        return true
    }

    suspend fun abandonRead(key: AppleCentralOperationKey): Boolean =
        mutex.withLock {
            val pending = reads[key] ?: return@withLock false
            pending.onComplete = null
            true
        }

    suspend fun quarantine(connection: AppleCentralConnectionKey, cause: Throwable): Boolean {
        val completions = mutex.withLock {
            if (!isActiveLocked(connection) || !quarantined.add(connection)) return false
            val callbacks = mutableListOf<() -> Unit>()
            writes[connection]?.let { pending ->
                pending.onComplete?.let { complete -> callbacks += { complete(CharacteristicWriteResult.Failed(cause)) } }
                pending.onComplete = null
            }
            reads.filterKeys { it.connection == connection }.values.forEach { pending ->
                pending.onComplete?.let { complete -> callbacks += { complete(AppleReadOutcome.Failed(cause)) } }
                pending.onComplete = null
            }
            subscriptions.filterKeys { it.connection == connection }.values.forEach { pending ->
                pending.onComplete?.let { complete -> callbacks += { complete(NotificationSubscriptionResult.Failed(cause)) } }
                pending.onComplete = null
            }
            callbacks
        }
        completions.forEach { it() }
        return true
    }

    suspend fun disconnect(connection: AppleCentralConnectionKey): Boolean {
        val completions = mutex.withLock {
            if (activeConnections[connection.peripheralUuid] != connection) return false
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
        activeConnections[connection.peripheralUuid] == connection && connection !in quarantined

    // One slot follows an attribute across pending and enabled states. A disable at
    // capacity must remain possible; admission cannot prevent releasing its own slot.
    private fun canRetainAttributeLocked(key: AppleCentralOperationKey): Boolean {
        val retained = reads.keys + subscriptions.keys + notifying
        return key in retained || retained.size < maximumAttributes
    }

    private fun removeConnectionLocked(
        connection: AppleCentralConnectionKey,
    ): List<() -> Unit> {
        quarantined.remove(connection)
        activeConnections.remove(connection.peripheralUuid)
        notifying.removeAll { it.connection == connection }
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
        reads.keys
            .filter { it.connection == connection }
            .forEach { key ->
                reads.remove(key)?.onComplete?.let { completion ->
                    callbacks += { completion(AppleReadOutcome.Disconnected) }
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

    private data class PendingRead(
        var onComplete: ((AppleReadOutcome) -> Unit)?,
    )
}

package dev.bluefalcon.plugins.queue

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadinessState
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralPlugin
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface QueueSendResult {
    data object Sent : QueueSendResult
    data object QueueFull : QueueSendResult
    data object PayloadTooLarge : QueueSendResult
    data object Disconnected : QueueSendResult
    data object Unsupported : QueueSendResult
    data class Failed(val cause: Throwable) : QueueSendResult
}

interface PeripheralQueue {
    suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode = NotificationMode.Notification,
    ): QueueSendResult
}

object QueuePlugin : PeripheralPluginFactory<QueuePlugin.Config, PeripheralQueue> {

    class Config : PeripheralPluginConfig() {
        var maxPendingItemsPerSession: Int = 64
        var maxPendingBytes: Int = 64 * 1024
    }

    override fun createConfig() = Config()

    override fun create(config: Config): PeripheralPlugin<PeripheralQueue> =
        InstalledQueuePlugin(config.snapshot())

    private fun Config.snapshot(): QueueConfig {
        require(maxPendingItemsPerSession > 0) {
            "maxPendingItemsPerSession must be positive"
        }
        require(maxPendingBytes > 0) { "maxPendingBytes must be positive" }
        return QueueConfig(
            maxPendingItemsPerSession = maxPendingItemsPerSession,
            maxPendingBytes = maxPendingBytes,
        )
    }
}

private class InstalledQueuePlugin(
    private val config: QueueConfig,
) : PeripheralPlugin<PeripheralQueue>, PeripheralQueue {

    private val mutex = Mutex()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val queues = mutableMapOf<SessionQueueKey, ArrayDeque<QueuedNotification>>()
    private val roundRobin = ArrayDeque<SessionQueueKey>()
    private val blockedSessions = mutableSetOf<SessionQueueKey>()
    private val sessionReadinessEpochs = mutableMapOf<PeripheralSessionId, Long>()
    private var installed = false
    private var closed = false
    private var queuedBytes = 0
    private var managerReadinessEpoch = 0L
    private var worker: Job? = null
    private var readinessCollector: Job? = null
    private var sessionCollector: Job? = null

    override fun install(
        peripheral: BlueFalconPeripheral,
        scope: CoroutineScope,
    ): PeripheralQueue {
        check(!installed) { "QueuePlugin instance is already installed" }
        installed = true
        readinessCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peripheral.notificationReadinessState.collect(::onNotificationReadinessState)
        }
        sessionCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peripheral.sessions.collect { activeSessions ->
                onSessionsChanged(activeSessions)
            }
        }
        worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (signal in wakeUp) {
                drainAvailable()
            }
        }
        return this
    }

    override suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): QueueSendResult {
        val maximum = session.maximumUpdateValueLength.value
        if (maximum != null && value.size > maximum) {
            return QueueSendResult.PayloadTooLarge
        }
        val queued = QueuedNotification(
            session = session,
            characteristic = characteristic,
            value = value,
            mode = mode,
        )
        val accepted = mutex.withLock {
            check(installed) { "QueuePlugin must be installed before send" }
            check(!closed) { "QueuePlugin is closed" }
            val sessionKey = queues.keys.firstOrNull { it.session === session }
                ?: SessionQueueKey(session)
            val sessionQueue = queues.getOrPut(sessionKey) { ArrayDeque() }
            if (
                sessionQueue.size >= config.maxPendingItemsPerSession ||
                queued.accountedByteCount > config.maxPendingBytes - queuedBytes
            ) {
                if (sessionQueue.isEmpty()) queues.remove(sessionKey)
                false
            } else {
                if (sessionQueue.isEmpty()) roundRobin.addLast(sessionKey)
                sessionQueue.addLast(queued)
                queuedBytes += queued.accountedByteCount
                true
            }
        }
        if (!accepted) return QueueSendResult.QueueFull
        wakeUp.trySend(Unit)
        return try {
            queued.completion.await()
        } catch (cause: CancellationException) {
            withContext(NonCancellable) {
                cancelIfPending(queued)
            }
            throw cause
        }
    }

    override suspend fun close() {
        val jobs = mutex.withLock {
            if (closed) return
            closed = true
            listOfNotNull(worker, readinessCollector, sessionCollector).also {
                worker = null
                readinessCollector = null
                sessionCollector = null
            }
        }
        wakeUp.close()
        jobs.forEach { it.cancelAndJoin() }
        completeDisconnected(drainAll())
    }

    private suspend fun drainAvailable() {
        while (true) {
            val candidate = mutex.withLock {
                val sessionKey = roundRobin.removeFirstOrNull() ?: return
                val item = queues[sessionKey]?.firstOrNull()
                if (item == null) {
                    queues.remove(sessionKey)
                    pruneSessionReadinessEpoch(sessionKey.id)
                    null
                } else {
                    item.submitting = true
                    NotificationAttempt(
                        sessionKey = sessionKey,
                        item = item,
                        managerReadinessEpoch = managerReadinessEpoch,
                        sessionReadinessEpoch =
                            sessionReadinessEpochs[sessionKey.id] ?: 0L,
                    )
                }
            } ?: continue
            val item = candidate.item
            val notificationResult = try {
                item.session.notify(
                    item.characteristic,
                    item.copyValueForSubmission(),
                    item.mode,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                NotificationResult.Failed(cause)
            }
            when (val result = notificationResult) {
                NotificationResult.Busy -> handleBusy(candidate)
                else -> completeHead(candidate.sessionKey, item, result.toQueueResult())
            }
        }
    }

    private suspend fun handleBusy(attempt: NotificationAttempt) {
        mutex.withLock {
            val queue = queues[attempt.sessionKey] ?: return
            if (queue.firstOrNull() !== attempt.item) return
            attempt.item.submitting = false
            if (attempt.item.cancelled) {
                removeItem(attempt.item)
                return
            }
            val readinessAdvanced =
                managerReadinessEpoch != attempt.managerReadinessEpoch ||
                    (sessionReadinessEpochs[attempt.sessionKey.id] ?: 0L) !=
                    attempt.sessionReadinessEpoch
            if (readinessAdvanced) {
                addEligibleSession(attempt.sessionKey)
            } else {
                blockedSessions += attempt.sessionKey
            }
        }
    }

    private suspend fun onNotificationReadinessState(
        state: NotificationReadinessState,
    ) {
        val unblocked = mutex.withLock {
            val managerAdvanced = state.managerEpoch > managerReadinessEpoch
            val sessionsAdvanced = blockedSessions.filter { sessionKey ->
                val currentEpoch = state.sessionEpochs[sessionKey.id] ?: 0L
                val previousEpoch = sessionReadinessEpochs[sessionKey.id] ?: 0L
                currentEpoch > previousEpoch
            }
            managerReadinessEpoch = state.managerEpoch
            val queuedSessionIds = queues.keys.mapTo(mutableSetOf()) { it.id }
            sessionReadinessEpochs.keys.retainAll(queuedSessionIds)
            queuedSessionIds.forEach { sessionId ->
                sessionReadinessEpochs[sessionId] =
                    state.sessionEpochs[sessionId] ?: 0L
            }

            if (managerAdvanced) {
                blockedSessions.toList().also { blockedSessions.clear() }
            } else {
                sessionsAdvanced.filter { blockedSessions.remove(it) }
            }
        }
        if (unblocked.isEmpty()) return
        mutex.withLock {
            unblocked.forEach(::addEligibleSession)
        }
        wakeUp.trySend(Unit)
    }

    private suspend fun cancelIfPending(item: QueuedNotification) {
        val removed = mutex.withLock {
            if (item.submitting) {
                item.cancelled = true
                false
            } else {
                removeItem(item)
            }
        }
        if (removed) wakeUp.trySend(Unit)
    }

    private suspend fun onSessionsChanged(activeSessions: Set<PeripheralSession>) {
        val disconnected = mutex.withLock {
            val removedSessionKeys = queues.keys.filterTo(mutableSetOf()) { key ->
                activeSessions.none { it === key.session }
            }
            drainSessions(removedSessionKeys)
        }
        completeDisconnected(disconnected)
    }

    private suspend fun drainAll(): List<QueuedNotification> = mutex.withLock {
        drainSessions(queues.keys.toSet())
    }

    private fun drainSessions(
        sessionKeys: Set<SessionQueueKey>,
    ): List<QueuedNotification> {
        val disconnected = mutableListOf<QueuedNotification>()
        sessionKeys.forEach { sessionKey ->
            val queue = queues.remove(sessionKey) ?: return@forEach
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                queuedBytes -= item.accountedByteCount
                disconnected += item
            }
            blockedSessions.remove(sessionKey)
            roundRobin.remove(sessionKey)
            pruneSessionReadinessEpoch(sessionKey.id)
        }
        return disconnected
    }

    private fun completeDisconnected(items: List<QueuedNotification>) {
        items.forEach { it.completion.complete(QueueSendResult.Disconnected) }
    }

    private fun removeItem(item: QueuedNotification): Boolean {
        val sessionKey = queues.keys.firstOrNull { it.session === item.session } ?: return false
        val queue = queues[sessionKey] ?: return false
        if (!queue.remove(item)) return false
        queuedBytes -= item.accountedByteCount
        if (queue.isEmpty()) {
            queues.remove(sessionKey)
            blockedSessions.remove(sessionKey)
            roundRobin.remove(sessionKey)
            pruneSessionReadinessEpoch(sessionKey.id)
        } else if (queue.firstOrNull()?.submitting != true) {
            blockedSessions.remove(sessionKey)
            addEligibleSession(sessionKey)
        }
        return true
    }

    private fun addEligibleSession(sessionKey: SessionQueueKey) {
        if (
            queues[sessionKey]?.isNotEmpty() == true &&
            sessionKey !in blockedSessions &&
            sessionKey !in roundRobin
        ) {
            roundRobin.addLast(sessionKey)
        }
    }

    private suspend fun completeHead(
        sessionKey: SessionQueueKey,
        item: QueuedNotification,
        result: QueueSendResult,
    ) {
        val completions = mutex.withLock {
            val queue = queues[sessionKey] ?: return@withLock emptyList()
            if (queue.firstOrNull() !== item) return@withLock emptyList()
            queue.removeFirst()
            queuedBytes -= item.accountedByteCount
            val pending = mutableListOf(item to result)
            if (result == QueueSendResult.Disconnected) {
                blockedSessions.remove(sessionKey)
                while (queue.isNotEmpty()) {
                    val dropped = queue.removeFirst()
                    queuedBytes -= dropped.accountedByteCount
                    pending += dropped to QueueSendResult.Disconnected
                }
            }
            if (queue.isEmpty()) {
                queues.remove(sessionKey)
                blockedSessions.remove(sessionKey)
                roundRobin.remove(sessionKey)
                pruneSessionReadinessEpoch(sessionKey.id)
            } else {
                roundRobin.addLast(sessionKey)
            }
            pending
        }
        completions.forEach { (notification, terminalResult) ->
            notification.completion.complete(terminalResult)
        }
    }

    private fun pruneSessionReadinessEpoch(sessionId: PeripheralSessionId) {
        if (queues.keys.none { it.id == sessionId }) {
            sessionReadinessEpochs.remove(sessionId)
        }
    }

    private fun NotificationResult.toQueueResult(): QueueSendResult = when (this) {
        NotificationResult.Sent -> QueueSendResult.Sent
        NotificationResult.Disconnected -> QueueSendResult.Disconnected
        NotificationResult.Unsupported -> QueueSendResult.Unsupported
        is NotificationResult.Failed -> QueueSendResult.Failed(cause)
        NotificationResult.Busy -> error("Busy is handled by the scheduler")
    }
}

private data class QueueConfig(
    val maxPendingItemsPerSession: Int,
    val maxPendingBytes: Int,
)

private class QueuedNotification(
    val session: PeripheralSession,
    val characteristic: GattCharacteristicId,
    value: ByteArray,
    val mode: NotificationMode,
) {
    private val copiedValue = value.copyOf()
    val accountedByteCount: Int = maxOf(copiedValue.size, 1)
    val completion = CompletableDeferred<QueueSendResult>()
    var submitting = false
    var cancelled = false

    fun copyValueForSubmission(): ByteArray = copiedValue.copyOf()
}

private data class NotificationAttempt(
    val sessionKey: SessionQueueKey,
    val item: QueuedNotification,
    val managerReadinessEpoch: Long,
    val sessionReadinessEpoch: Long,
)

private class SessionQueueKey(
    val session: PeripheralSession,
) {
    val id: PeripheralSessionId
        get() = session.id
}

package dev.bluefalcon.plugins.queue

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.PeripheralPlugin
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.NotificationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class QueueOverflowPolicy {
    RejectNewest,
}

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
        var overflowPolicy: QueueOverflowPolicy = QueueOverflowPolicy.RejectNewest
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
            overflowPolicy = overflowPolicy,
        )
    }
}

private class InstalledQueuePlugin(
    private val config: QueueConfig,
) : PeripheralPlugin<PeripheralQueue>, PeripheralQueue {

    private val mutex = Mutex()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val queues = mutableMapOf<PeripheralSessionId, ArrayDeque<QueuedNotification>>()
    private val roundRobin = ArrayDeque<PeripheralSessionId>()
    private val blockedSessions = mutableSetOf<PeripheralSessionId>()
    private val sessionReadinessEpochs = mutableMapOf<PeripheralSessionId, Long>()
    private var installed = false
    private var closed = false
    private var queuedBytes = 0
    private var managerReadinessEpoch = 0L
    private var worker: Job? = null
    private var readinessCollector: Job? = null

    override fun install(
        peripheral: BlueFalconPeripheral,
        scope: CoroutineScope,
    ): PeripheralQueue {
        check(!installed) { "QueuePlugin instance is already installed" }
        installed = true
        worker = scope.launch {
            for (signal in wakeUp) {
                drainAvailable()
            }
        }
        readinessCollector = scope.launch {
            peripheral.notificationReadiness.collect(::onNotificationReady)
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
            value = value.copyOf(),
            mode = mode,
        )
        val accepted = mutex.withLock {
            check(installed) { "QueuePlugin must be installed before send" }
            check(!closed) { "QueuePlugin is closed" }
            val sessionQueue = queues.getOrPut(session.id) { ArrayDeque() }
            if (
                sessionQueue.size >= config.maxPendingItemsPerSession ||
                queuedBytes + queued.value.size > config.maxPendingBytes
            ) {
                if (sessionQueue.isEmpty()) queues.remove(session.id)
                false
            } else {
                if (sessionQueue.isEmpty()) roundRobin.addLast(session.id)
                sessionQueue.addLast(queued)
                queuedBytes += queued.value.size
                true
            }
        }
        if (!accepted) return QueueSendResult.QueueFull
        wakeUp.trySend(Unit)
        return try {
            queued.completion.await()
        } catch (cause: CancellationException) {
            cancelIfPending(queued)
            throw cause
        }
    }

    override suspend fun close() {
        val job = mutex.withLock {
            if (closed) return
            closed = true
            worker.also { worker = null }
        }
        wakeUp.close()
        readinessCollector?.cancelAndJoin()
        readinessCollector = null
        job?.cancelAndJoin()
    }

    private suspend fun drainAvailable() {
        while (true) {
            val candidate = mutex.withLock {
                val sessionId = roundRobin.removeFirstOrNull() ?: return
                val item = queues[sessionId]?.firstOrNull()
                if (item == null) {
                    queues.remove(sessionId)
                    null
                } else {
                    item.submitting = true
                    NotificationAttempt(
                        sessionId = sessionId,
                        item = item,
                        managerReadinessEpoch = managerReadinessEpoch,
                        sessionReadinessEpoch = sessionReadinessEpochs[sessionId] ?: 0L,
                    )
                }
            } ?: continue
            val item = candidate.item
            when (val result = item.session.notify(item.characteristic, item.value, item.mode)) {
                NotificationResult.Busy -> handleBusy(candidate)
                else -> completeHead(candidate.sessionId, item, result.toQueueResult())
            }
        }
    }

    private suspend fun handleBusy(attempt: NotificationAttempt) {
        mutex.withLock {
            val queue = queues[attempt.sessionId] ?: return
            if (queue.firstOrNull() !== attempt.item) return
            attempt.item.submitting = false
            if (attempt.item.cancelled) {
                removeItem(attempt.item)
                return
            }
            val readinessAdvanced =
                managerReadinessEpoch != attempt.managerReadinessEpoch ||
                    (sessionReadinessEpochs[attempt.sessionId] ?: 0L) !=
                    attempt.sessionReadinessEpoch
            if (readinessAdvanced) {
                addEligibleSession(attempt.sessionId)
            } else {
                blockedSessions += attempt.sessionId
            }
        }
    }

    private suspend fun onNotificationReady(readiness: NotificationReadiness) {
        val unblocked = mutex.withLock {
            when (readiness) {
                NotificationReadiness.Manager -> {
                    managerReadinessEpoch++
                    blockedSessions.toList().also { blockedSessions.clear() }
                }

                is NotificationReadiness.Session -> {
                    sessionReadinessEpochs[readiness.sessionId] =
                        (sessionReadinessEpochs[readiness.sessionId] ?: 0L) + 1L
                    if (blockedSessions.remove(readiness.sessionId)) {
                        listOf(readiness.sessionId)
                    } else {
                        emptyList()
                    }
                }
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

    private fun removeItem(item: QueuedNotification): Boolean {
        val sessionId = item.session.id
        val queue = queues[sessionId] ?: return false
        if (!queue.remove(item)) return false
        queuedBytes -= item.value.size
        if (queue.isEmpty()) {
            queues.remove(sessionId)
            blockedSessions.remove(sessionId)
            roundRobin.remove(sessionId)
        } else if (queue.firstOrNull()?.submitting != true) {
            blockedSessions.remove(sessionId)
            addEligibleSession(sessionId)
        }
        return true
    }

    private fun addEligibleSession(sessionId: PeripheralSessionId) {
        if (
            queues[sessionId]?.isNotEmpty() == true &&
            sessionId !in blockedSessions &&
            sessionId !in roundRobin
        ) {
            roundRobin.addLast(sessionId)
        }
    }

    private suspend fun completeHead(
        sessionId: PeripheralSessionId,
        item: QueuedNotification,
        result: QueueSendResult,
    ) {
        val completions = mutex.withLock {
            val queue = queues[sessionId] ?: return@withLock emptyList()
            if (queue.firstOrNull() !== item) return@withLock emptyList()
            queue.removeFirst()
            queuedBytes -= item.value.size
            val pending = mutableListOf(item to result)
            if (result == QueueSendResult.Disconnected) {
                blockedSessions.remove(sessionId)
                while (queue.isNotEmpty()) {
                    val dropped = queue.removeFirst()
                    queuedBytes -= dropped.value.size
                    pending += dropped to QueueSendResult.Disconnected
                }
            }
            if (queue.isEmpty()) {
                queues.remove(sessionId)
            } else {
                roundRobin.addLast(sessionId)
            }
            pending
        }
        completions.forEach { (notification, terminalResult) ->
            notification.completion.complete(terminalResult)
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
    val overflowPolicy: QueueOverflowPolicy,
)

private class QueuedNotification(
    val session: PeripheralSession,
    val characteristic: GattCharacteristicId,
    value: ByteArray,
    val mode: NotificationMode,
) {
    private val copiedValue = value.copyOf()
    val value: ByteArray
        get() = copiedValue.copyOf()
    val completion = CompletableDeferred<QueueSendResult>()
    var submitting = false
    var cancelled = false
}

private data class NotificationAttempt(
    val sessionId: PeripheralSessionId,
    val item: QueuedNotification,
    val managerReadinessEpoch: Long,
    val sessionReadinessEpoch: Long,
)

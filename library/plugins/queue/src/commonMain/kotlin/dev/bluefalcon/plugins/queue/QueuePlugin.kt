package dev.bluefalcon.plugins.queue

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralPlugin
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.NotificationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
    private var installed = false
    private var closed = false
    private var queuedBytes = 0
    private var worker: Job? = null

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
        return queued.completion.await()
    }

    override suspend fun close() {
        val job = mutex.withLock {
            if (closed) return
            closed = true
            worker.also { worker = null }
        }
        wakeUp.close()
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
                    sessionId to item
                }
            } ?: continue
            val (sessionId, item) = candidate
            when (val result = item.session.notify(item.characteristic, item.value, item.mode)) {
                NotificationResult.Busy -> return
                else -> completeHead(sessionId, item, result.toQueueResult())
            }
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
}

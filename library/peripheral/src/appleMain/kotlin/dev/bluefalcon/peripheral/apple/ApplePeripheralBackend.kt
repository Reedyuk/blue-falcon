package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWrite
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteBatchRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import dev.bluefalcon.peripheral.internal.BackendGattResponder
import dev.bluefalcon.peripheral.internal.PeripheralBackend
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSLock

internal class ApplePeripheralBackend(
    private val stack: ApplePeripheralStack,
    private val logger: Logger?,
) : PeripheralBackend {
    private val stateLock = NSLock()
    private val lifecycleMutex = Mutex()
    private var state: BackendState = BackendState.Stopped
    private var generation = 0L
    private var eventSink: PeripheralBackendEventSink? = null
    private val activeSessions = mutableSetOf<PeripheralSessionId>()
    private val maximumLengths = mutableMapOf<PeripheralSessionId, Int>()
    private val subscriptions =
        mutableMapOf<PeripheralSessionId, MutableSet<GattCharacteristicId>>()
    private var supportedModes = emptyMap<GattCharacteristicId, Set<NotificationMode>>()
    private val eventDeliveries = ArrayDeque<EventDelivery>()
    private var eventDeliveryOwner = false

    override val capabilities = PeripheralCapabilities(
        localGattServer = true,
        connectableAdvertising = true,
        multiCentral = true,
        targetedNotifications = true,
        notificationReadiness = true,
        maximumUpdateValueLength = true,
        forcedDisconnect = false,
        connectionLifecycleVisibility = false,
        preparedWrites = false,
        stateRestoration = true,
    )

    override suspend fun start(
        config: PeripheralConfig,
        eventSink: PeripheralBackendEventSink,
    ) = lifecycleMutex.withLock {
        val startGeneration = locked {
            when (state) {
                BackendState.Stopped -> Unit
                BackendState.Closed -> throw PeripheralLifecycleException(
                    "Apple peripheral backend is closed",
                )
                else -> throw PeripheralLifecycleException(
                    "Apple peripheral backend is not stopped",
                )
            }
            val allocatedGeneration = ++generation
            state = BackendState.Starting(allocatedGeneration)
            this.eventSink = eventSink
            supportedModes = notificationModes(config)
            allocatedGeneration
        }
        val listener = object : ApplePeripheralStackListener {
            override fun onEvent(event: AppleGattEvent) {
                onStackEvent(startGeneration, event)
            }

            override fun onPlatformFailure(cause: Throwable) {
                publishPlatformFailure(startGeneration, cause)
            }
        }

        try {
            val openResult = stack.open(config, listener)
            openResult.restoredSessions.forEach { restored ->
                restoreSession(startGeneration, restored)
            }
            val published = locked {
                if (state == BackendState.Starting(startGeneration)) {
                    state = BackendState.Running(startGeneration)
                    true
                } else {
                    false
                }
            }
            if (!published) {
                throw PeripheralLifecycleException(
                    "Apple peripheral start was superseded by shutdown",
                )
            }
        } catch (cause: Throwable) {
            withContext(NonCancellable) {
                runCatching { stack.stopAdvertising() }
                    .onFailure { logger?.error("ApplePeripheral: rollback advertising failed", it) }
                runCatching { stack.clearServices() }
                    .onFailure { logger?.error("ApplePeripheral: rollback services failed", it) }
            }
            locked {
                if (state == BackendState.Starting(startGeneration)) {
                    clearRuntimeState()
                    state = BackendState.Stopped
                }
            }
            throw cause
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        val shouldStop = locked {
            when (state) {
                BackendState.Stopped, BackendState.Closed -> false
                is BackendState.Starting, is BackendState.Running -> {
                    state = BackendState.Stopping(++generation)
                    true
                }
                is BackendState.Stopping -> false
            }
        }
        if (!shouldStop) return@withLock

        try {
            stack.stopAdvertising()
            stack.clearServices()
        } finally {
            locked {
                clearRuntimeState()
                state = BackendState.Stopped
            }
        }
    }

    override suspend fun close() = lifecycleMutex.withLock {
        val previousState = locked {
            if (state == BackendState.Closed) return@withLock
            state.also { state = BackendState.Closed }
        }

        try {
            if (previousState != BackendState.Stopped) {
                stack.stopAdvertising()
                stack.clearServices()
            }
        } finally {
            locked { clearRuntimeState() }
            stack.close()
        }
    }

    override suspend fun notify(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult = NotificationResult.Unsupported

    override suspend fun disconnect(sessionId: PeripheralSessionId): DisconnectResult =
        DisconnectResult.Unsupported

    private fun onStackEvent(eventGeneration: Long, event: AppleGattEvent) {
        var staleResponse: AppleGattResponse? = null
        val owner = locked {
            val sink = activeSink(eventGeneration)
            if (sink == null) {
                staleResponse = event.failureResponse()
                false
            } else {
                val callback = handleEventLocked(eventGeneration, event, sink)
                callback?.let {
                    enqueueEventDeliveryLocked(EventDelivery(eventGeneration, it))
                } ?: false
            }
        }
        staleResponse?.let(::sendStaleRequestResponse)
        if (owner) drainEventDeliveries()
    }

    private fun handleEventLocked(
        eventGeneration: Long,
        event: AppleGattEvent,
        sink: PeripheralBackendEventSink,
    ): (() -> Unit)? = when (event) {
        is AppleGattEvent.CharacteristicRead -> {
            val sessionDelivery = ensureSessionLocked(
                sink,
                event.sessionId,
                event.maximumUpdateValueLength,
            )
            val request = BackendCharacteristicReadRequest(
                sessionId = event.sessionId,
                serviceId = event.serviceId,
                characteristicId = event.characteristicId,
                offset = event.offset,
                responder = createGattResponder(
                    eventGeneration,
                    event.sessionId,
                    event.requestToken,
                ),
            )
            val callback: () -> Unit = {
                sessionDelivery?.deliver()
                sink.onRequest(request)
            }
            callback
        }

        is AppleGattEvent.CharacteristicWrite -> {
            val sessionDelivery = ensureSessionLocked(
                sink,
                event.sessionId,
                event.maximumUpdateValueLength,
            )
            val write = event.copiedWrite
            val request = BackendCharacteristicWriteRequest(
                sessionId = event.sessionId,
                serviceId = write.serviceId,
                characteristicId = write.characteristicId,
                offset = write.offset,
                value = write.value,
                preparedWrite = false,
                responder = createGattResponder(
                    eventGeneration,
                    event.sessionId,
                    event.requestToken,
                ),
            )
            val callback: () -> Unit = {
                sessionDelivery?.deliver()
                sink.onRequest(request)
            }
            callback
        }

        is AppleGattEvent.CharacteristicWriteBatch -> {
            val sessionDelivery = ensureSessionLocked(
                sink,
                event.sessionId,
                event.maximumUpdateValueLength,
            )
            val request = BackendCharacteristicWriteBatchRequest(
                sessionId = event.sessionId,
                writes = event.writes.map { write ->
                    BackendCharacteristicWrite(
                        serviceId = write.serviceId,
                        characteristicId = write.characteristicId,
                        offset = write.offset,
                        value = write.value,
                    )
                },
                responder = createGattResponder(
                    eventGeneration,
                    event.sessionId,
                    event.requestToken,
                ),
            )
            val callback: () -> Unit = {
                sessionDelivery?.deliver()
                sink.onRequest(request)
            }
            callback
        }

        is AppleGattEvent.Subscribed -> subscriptionDeliveryLocked(
            sink = sink,
            sessionId = event.sessionId,
            maximumUpdateValueLength = event.maximumUpdateValueLength,
            characteristicId = event.characteristicId,
            subscribed = true,
        )

        is AppleGattEvent.Unsubscribed -> subscriptionDeliveryLocked(
            sink = sink,
            sessionId = event.sessionId,
            maximumUpdateValueLength = event.maximumUpdateValueLength,
            characteristicId = event.characteristicId,
            subscribed = false,
        )

        AppleGattEvent.NotificationReady -> null
    }

    private fun subscriptionDeliveryLocked(
        sink: PeripheralBackendEventSink,
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int,
        characteristicId: GattCharacteristicId,
        subscribed: Boolean,
    ): () -> Unit {
        val sessionDelivery = ensureSessionLocked(
            sink,
            sessionId,
            maximumUpdateValueLength,
        )
        val sessionSubscriptions = subscriptions.getOrPut(sessionId, ::mutableSetOf)
        if (subscribed) {
            sessionSubscriptions += characteristicId
        } else {
            sessionSubscriptions -= characteristicId
        }
        val delivery = SubscriptionDelivery(
            sessionDelivery = sessionDelivery,
            sink = sink,
            sessionId = sessionId,
            subscriptions = sessionSubscriptions.toSet(),
        )
        return delivery::deliver
    }

    private fun restoreSession(
        startGeneration: Long,
        restored: AppleRestoredSession,
    ) {
        val owner = locked {
            val sink = activeSink(startGeneration) ?: return
            val sessionDelivery = ensureSessionLocked(
                sink,
                restored.sessionId,
                restored.maximumUpdateValueLength,
            )
            subscriptions[restored.sessionId] = restored.subscriptions.toMutableSet()
            val delivery = SubscriptionDelivery(
                sessionDelivery = sessionDelivery,
                sink = sink,
                sessionId = restored.sessionId,
                subscriptions = restored.subscriptions,
            )
            enqueueEventDeliveryLocked(EventDelivery(startGeneration, delivery::deliver))
        }
        if (owner) drainEventDeliveries()
    }

    private fun ensureSessionLocked(
        sink: PeripheralBackendEventSink,
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int,
    ): SessionDelivery? {
        val previousMaximum = maximumLengths.put(sessionId, maximumUpdateValueLength)
        return if (activeSessions.add(sessionId)) {
            SessionDelivery.Opened(sink, sessionId, maximumUpdateValueLength)
        } else if (previousMaximum != maximumUpdateValueLength) {
            SessionDelivery.MaximumChanged(sink, sessionId, maximumUpdateValueLength)
        } else {
            null
        }
    }

    private fun createGattResponder(
        eventGeneration: Long,
        sessionId: PeripheralSessionId,
        requestToken: AppleRequestToken,
    ): BackendGattResponder {
        val responseLock = NSLock()
        var pending = true
        return BackendGattResponder { status, value ->
            responseLock.lock()
            val accepted = try {
                pending.also { pending = false }
            } finally {
                responseLock.unlock()
            }
            if (!accepted) return@BackendGattResponder

            val active = locked { activeSink(eventGeneration) != null }
            if (!active) return@BackendGattResponder
            val sent = try {
                stack.sendResponse(
                    AppleGattResponse(
                        sessionId = sessionId,
                        requestToken = requestToken,
                        status = status,
                        value = value,
                    ),
                )
            } catch (cause: Throwable) {
                publishPlatformFailure(eventGeneration, cause)
                return@BackendGattResponder
            }
            if (!sent) {
                publishPlatformFailure(
                    eventGeneration,
                    AppleGattResponseException(sessionId, requestToken),
                )
            }
        }
    }

    private fun AppleGattEvent.failureResponse(): AppleGattResponse? {
        val request = when (this) {
            is AppleGattEvent.CharacteristicRead -> sessionId to requestToken
            is AppleGattEvent.CharacteristicWrite -> sessionId to requestToken
            is AppleGattEvent.CharacteristicWriteBatch -> sessionId to requestToken
            else -> null
        } ?: return null
        return AppleGattResponse(
            sessionId = request.first,
            requestToken = request.second,
            status = GattResponseStatus.UnlikelyError,
            value = null,
        )
    }

    private fun sendStaleRequestResponse(response: AppleGattResponse) {
        runCatching { stack.sendResponse(response) }
            .onFailure { logger?.warn("Failed to reject stale Apple GATT request", it) }
    }

    private fun enqueueEventDeliveryLocked(delivery: EventDelivery): Boolean {
        eventDeliveries.addLast(delivery)
        if (eventDeliveryOwner) return false
        eventDeliveryOwner = true
        return true
    }

    private fun drainEventDeliveries() {
        while (true) {
            val delivery = locked {
                eventDeliveries.removeFirstOrNull().also { next ->
                    if (next == null) eventDeliveryOwner = false
                }
            } ?: return
            val active = locked { activeSink(delivery.generation) != null }
            if (active) {
                runCatching(delivery.callback)
                    .onFailure { logger?.warn("Apple peripheral event delivery failed", it) }
            }
        }
    }

    private fun activeSink(eventGeneration: Long): PeripheralBackendEventSink? {
        val active = when (val current = state) {
            is BackendState.Starting -> current.generation == eventGeneration
            is BackendState.Running -> current.generation == eventGeneration
            else -> false
        }
        return eventSink.takeIf { active }
    }

    private fun publishPlatformFailure(eventGeneration: Long, cause: Throwable) {
        val owner = locked {
            val sink = activeSink(eventGeneration) ?: return
            enqueueEventDeliveryLocked(
                EventDelivery(eventGeneration) { sink.onPlatformFailure(cause) },
            )
        }
        if (owner) drainEventDeliveries()
    }

    private fun clearRuntimeState() {
        eventSink = null
        activeSessions.clear()
        maximumLengths.clear()
        subscriptions.clear()
        supportedModes = emptyMap()
        eventDeliveries.clear()
        eventDeliveryOwner = false
    }

    private inline fun <T> locked(block: () -> T): T {
        stateLock.lock()
        return try {
            block()
        } finally {
            stateLock.unlock()
        }
    }

    private fun notificationModes(
        config: PeripheralConfig,
    ): Map<GattCharacteristicId, Set<NotificationMode>> = buildMap {
        config.advertiseConfig.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                val modes = buildSet {
                    if (CharacteristicProperty.NOTIFY in characteristic.properties) {
                        add(NotificationMode.Notification)
                    }
                    if (CharacteristicProperty.INDICATE in characteristic.properties) {
                        add(NotificationMode.Indication)
                    }
                }
                if (modes.isNotEmpty()) {
                    put(GattCharacteristicId(Uuid.parse(characteristic.uuid)), modes)
                }
            }
        }
    }

    private sealed interface BackendState {
        data object Stopped : BackendState
        data class Starting(val generation: Long) : BackendState
        data class Running(val generation: Long) : BackendState
        data class Stopping(val generation: Long) : BackendState
        data object Closed : BackendState
    }

    private class EventDelivery(
        val generation: Long,
        val callback: () -> Unit,
    )

    private sealed interface SessionDelivery {
        fun deliver()

        class Opened(
            private val sink: PeripheralBackendEventSink,
            private val sessionId: PeripheralSessionId,
            private val maximumUpdateValueLength: Int,
        ) : SessionDelivery {
            override fun deliver() {
                sink.onSessionOpened(sessionId, maximumUpdateValueLength)
            }
        }

        class MaximumChanged(
            private val sink: PeripheralBackendEventSink,
            private val sessionId: PeripheralSessionId,
            private val maximumUpdateValueLength: Int,
        ) : SessionDelivery {
            override fun deliver() {
                sink.onMaximumUpdateValueLengthChanged(
                    sessionId,
                    maximumUpdateValueLength,
                )
            }
        }
    }

    private class SubscriptionDelivery(
        private val sessionDelivery: SessionDelivery?,
        private val sink: PeripheralBackendEventSink,
        private val sessionId: PeripheralSessionId,
        private val subscriptions: Set<GattCharacteristicId>,
    ) {
        fun deliver() {
            sessionDelivery?.deliver()
            sink.onSubscriptionsChanged(sessionId, subscriptions)
        }
    }
}

internal class AppleGattResponseException(
    sessionId: PeripheralSessionId,
    requestToken: AppleRequestToken,
) : IllegalStateException(
    "Core Bluetooth rejected GATT response for session $sessionId and token ${requestToken.value}",
)

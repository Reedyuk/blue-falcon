package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendSessionToken
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWrite
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteBatchRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import dev.bluefalcon.peripheral.internal.BackendGattResponder
import dev.bluefalcon.peripheral.internal.PeripheralRequestAdmission
import dev.bluefalcon.peripheral.internal.PeripheralResourceOverflowException
import dev.bluefalcon.peripheral.internal.PeripheralBackend
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.CancellationException
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
    private val sessionTokens = mutableMapOf<PeripheralSessionId, BackendSessionToken>()
    private val sessionTargets = mutableMapOf<PeripheralSessionId, AppleSessionTarget>()
    private val activeSessions = mutableSetOf<PeripheralSessionId>()
    private val maximumLengths = mutableMapOf<PeripheralSessionId, Int>()
    private val subscriptions =
        mutableMapOf<PeripheralSessionId, MutableSet<GattCharacteristicId>>()
    private var supportedModes = emptyMap<GattCharacteristicId, Set<NotificationMode>>()
    private val eventDeliveries = ArrayDeque<EventDelivery>()
    private var eventDeliveryOwner = false
    private val deliveryAdmission = PeripheralRequestAdmission()
    private var resourceFailureGeneration: Long? = null
    private var pendingResourceFailure: Pair<PeripheralBackendEventSink, Throwable>? = null

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
            resourceFailureGeneration = null
            state = BackendState.Starting(allocatedGeneration)
            this.eventSink = eventSink
            supportedModes = notificationModes(config)
            allocatedGeneration
        }
        val listener = object : ApplePeripheralStackListener {
            override fun onEvent(event: AppleGattEvent) {
                onStackEvent(startGeneration, event)
            }

            override fun onResourceOverflow(cause: Throwable) {
                val owner = locked { activeSink(startGeneration)?.let { markResourceFailureLocked(startGeneration, it, cause) }; false }
                dispatchDeliveries(owner)
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
                    sessionTokens.values.forEach { it.retire() }
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
            state.also {
                sessionTokens.values.forEach { it.retire() }
                state = BackendState.Closed
            }
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
    ): NotificationResult = lifecycleMutex.withLock {
        notifyLocked(sessionId, characteristic, value, mode, null)
    }

    override suspend fun notify(
        token: BackendSessionToken,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult = lifecycleMutex.withLock {
        notifyLocked(token.sessionId, characteristic, value, mode, token)
    }

    private fun notifyLocked(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
        expectedToken: BackendSessionToken?,
    ): NotificationResult {
        val request = locked {
            if (sessionId !in activeSessions || sessionTokens[sessionId]?.isCurrent() != true ||
                (expectedToken != null && (sessionTokens[sessionId] !== expectedToken.backendToken || !expectedToken.isCurrent()))) {
                return NotificationResult.Disconnected
            }
            if (supportedModes[characteristic]?.contains(mode) != true) {
                return NotificationResult.Unsupported
            }
            if (characteristic !in subscriptions[sessionId].orEmpty()) {
                return NotificationResult.Unsupported
            }
            val maximumLength = maximumLengths[sessionId]
                ?: return NotificationResult.Disconnected
            if (value.size > maximumLength) {
                return NotificationResult.Failed(
                    AppleNotificationValueTooLongException(value.size, maximumLength),
                )
            }
            AppleNotificationRequest(sessionId, characteristic, mode, value)
        }

        return try {
            when (val result = stack.notify(request)) {
                AppleNotificationStartResult.Accepted -> NotificationResult.Sent
                AppleNotificationStartResult.Busy -> NotificationResult.Busy
                AppleNotificationStartResult.Disconnected -> NotificationResult.Disconnected
                is AppleNotificationStartResult.Rejected -> NotificationResult.Failed(result.cause)
            }
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            NotificationResult.Failed(cause)
        }
    }

    override suspend fun retireSession(token: BackendSessionToken) = lifecycleMutex.withLock {
        var target: AppleSessionTarget? = null
        val retired = locked {
            if (sessionTokens[token.sessionId] !== token.backendToken) return@locked false
            target = sessionTargets.remove(token.sessionId)
            sessionTokens.remove(token.sessionId)?.retire()
            activeSessions.remove(token.sessionId)
            maximumLengths.remove(token.sessionId)
            subscriptions.remove(token.sessionId)
            true
        }
        if (retired) target?.retire() ?: stack.retireSession(token.sessionId)
    }

    override suspend fun disconnect(sessionId: PeripheralSessionId): DisconnectResult =
        DisconnectResult.Unsupported

    private fun onStackEvent(eventGeneration: Long, event: AppleGattEvent) {
        var staleResponse: AppleGattResponse? = null
        val owner = locked {
            val sink = activeSink(eventGeneration)
            if (event.target?.isCurrent() == false) { staleResponse = event.failureResponse(); return@locked false }
            if (sink == null) {
                staleResponse = event.failureResponse()
                false
            } else {
                val bytes = when (event) {
                    is AppleGattEvent.CharacteristicWrite -> event.payloadBytes
                    is AppleGattEvent.CharacteristicWriteBatch -> event.payloadBytes
                    else -> 0
                }
                val permit = deliveryAdmission.acquire(bytes, false)
                if (permit == null) {
                    staleResponse = event.failureResponse()
                    markResourceFailureLocked(eventGeneration, sink)
                    false
                } else {
                    try {
                        val callback = handleEventLocked(eventGeneration, event, sink)
                        if (callback == null) { permit.delivered(); false }
                        else enqueueEventDeliveryLocked(EventDelivery(eventGeneration, permit, callback))
                    } catch (cause: PeripheralResourceOverflowException) {
                        permit.delivered()
                        staleResponse = event.failureResponse()
                        markResourceFailureLocked(eventGeneration, sink, cause)
                        false
                    }
                }
            }
        }
        staleResponse?.let(::sendStaleRequestResponse)
        dispatchDeliveries(owner)
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
                event.target,
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
            val token = requireNotNull(sessionTokens[event.sessionId])
            val callback: () -> Unit = {
                sessionDelivery?.deliver()
                sink.onRequest(token, request)
            }
            callback
        }

        is AppleGattEvent.CharacteristicWrite -> {
            val sessionDelivery = ensureSessionLocked(
                sink,
                event.sessionId,
                event.maximumUpdateValueLength,
                event.target,
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
            val token = requireNotNull(sessionTokens[event.sessionId])
            val callback: () -> Unit = {
                sessionDelivery?.deliver()
                sink.onRequest(token, request)
            }
            callback
        }

        is AppleGattEvent.CharacteristicWriteBatch -> {
            val sessionDelivery = ensureSessionLocked(
                sink,
                event.sessionId,
                event.maximumUpdateValueLength,
                event.target,
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
            val token = requireNotNull(sessionTokens[event.sessionId])
            val callback: () -> Unit = {
                sessionDelivery?.deliver()
                sink.onRequest(token, request)
            }
            callback
        }

        is AppleGattEvent.Subscribed -> subscriptionDeliveryLocked(
            sink = sink,
            sessionId = event.sessionId,
            maximumUpdateValueLength = event.maximumUpdateValueLength,
            characteristicId = event.characteristicId,
            subscribed = true,
            target = event.target,
        )

        is AppleGattEvent.Unsubscribed -> subscriptionDeliveryLocked(
            sink = sink,
            sessionId = event.sessionId,
            maximumUpdateValueLength = event.maximumUpdateValueLength,
            characteristicId = event.characteristicId,
            subscribed = false,
            target = event.target,
        )

        AppleGattEvent.NotificationReady -> {
            { sink.onNotificationReady(NotificationReadiness.Manager) }
        }
    }

    private fun subscriptionDeliveryLocked(
        sink: PeripheralBackendEventSink,
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int,
        characteristicId: GattCharacteristicId,
        subscribed: Boolean,
        target: AppleSessionTarget?,
    ): () -> Unit {
        val sessionDelivery = ensureSessionLocked(
            sink,
            sessionId,
            maximumUpdateValueLength,
            target,
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
            token = requireNotNull(sessionTokens[sessionId]),
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
                restored.target,
            )
            subscriptions[restored.sessionId] = restored.subscriptions.toMutableSet()
            val delivery = SubscriptionDelivery(
                sessionDelivery = sessionDelivery,
                sink = sink,
                token = requireNotNull(sessionTokens[restored.sessionId]),
                subscriptions = restored.subscriptions,
            )
            enqueueEventDeliveryLocked(EventDelivery(startGeneration, callback = delivery::deliver))
        }
        dispatchDeliveries(owner)
    }

    private fun ensureSessionLocked(
        sink: PeripheralBackendEventSink,
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int,
        target: AppleSessionTarget? = null,
    ): SessionDelivery? {
        if (target != null && sessionTargets[sessionId]?.let { it !== target } == true) {
            sessionTokens.remove(sessionId)?.retire()
            activeSessions.remove(sessionId)
            maximumLengths.remove(sessionId)
            subscriptions.remove(sessionId)
        }
        if (sessionId !in activeSessions && activeSessions.size >= 256) throw PeripheralResourceOverflowException()
        val previousMaximum = maximumLengths.put(sessionId, maximumUpdateValueLength)
        return if (activeSessions.add(sessionId)) {
            val token = BackendSessionToken(sessionId) { target?.isCurrent() != false }
            sessionTokens[sessionId] = token
            if (target != null) sessionTargets[sessionId] = target
            SessionDelivery.Opened(sink, token, maximumUpdateValueLength)
        } else if (previousMaximum != maximumUpdateValueLength) {
            SessionDelivery.MaximumChanged(sink, requireNotNull(sessionTokens[sessionId]), maximumUpdateValueLength)
        } else {
            null
        }
    }

    private fun createGattResponder(
        eventGeneration: Long,
        sessionId: PeripheralSessionId,
        requestToken: AppleRequestToken,
    ): BackendGattResponder {
        val token = requireNotNull(sessionTokens[sessionId])
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

            val active = locked { activeSink(eventGeneration) != null && sessionTokens[sessionId] === token && token.isCurrent() }
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
                publishPlatformFailure(eventGeneration, cause, token)
                return@BackendGattResponder
            }
            if (!sent) {
                publishPlatformFailure(
                    eventGeneration,
                    AppleGattResponseException(sessionId, requestToken),
                    token,
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

    private fun markResourceFailureLocked(generation: Long, sink: PeripheralBackendEventSink, cause: Throwable = PeripheralResourceOverflowException()) {
        if (resourceFailureGeneration == generation) return
        resourceFailureGeneration = generation
        sessionTokens.values.forEach { it.retire() }
        clearEventDeliveriesLocked()
        pendingResourceFailure = sink to cause
    }

    private fun clearEventDeliveriesLocked() {
        eventDeliveries.forEach { it.permit?.delivered() }
        eventDeliveries.clear()
    }

    private fun dispatchDeliveries(owner: Boolean) {
        val failure = locked { pendingResourceFailure.also { pendingResourceFailure = null } }
        failure?.let { it.first.onResourceOverflow(it.second) }
        if (owner) drainEventDeliveries()
    }

    private fun enqueueEventDeliveryLocked(delivery: EventDelivery): Boolean {
        if (resourceFailureGeneration == delivery.generation) { delivery.permit?.delivered(); return false }
        val admitted = delivery.permit ?: deliveryAdmission.acquire(0, false)
        if (admitted == null) {
            eventSink?.let { markResourceFailureLocked(delivery.generation, it) }
            return false
        }
        eventDeliveries.addLast(delivery.copy(permit = admitted))
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
            try {
                if (active) runCatching(delivery.callback)
                    .onFailure { logger?.warn("Apple peripheral event delivery failed", it) }
            } finally { delivery.permit?.delivered() }
        }
    }

    private fun activeSink(eventGeneration: Long): PeripheralBackendEventSink? {
        if (resourceFailureGeneration == eventGeneration) return null
        val active = when (val current = state) {
            is BackendState.Starting -> current.generation == eventGeneration
            is BackendState.Running -> current.generation == eventGeneration
            else -> false
        }
        return eventSink.takeIf { active }
    }

    private fun publishPlatformFailure(eventGeneration: Long, cause: Throwable, expectedToken: BackendSessionToken? = null) {
        val owner = locked {
            val sink = activeSink(eventGeneration) ?: return
            if (expectedToken != null && (sessionTokens[expectedToken.sessionId] !== expectedToken || !expectedToken.isCurrent())) return
            enqueueEventDeliveryLocked(
                EventDelivery(eventGeneration) {
                    if (expectedToken == null) sink.onPlatformFailure(cause)
                    else if (expectedToken.isCurrent()) sink.onPlatformFailure(expectedToken, cause)
                },
            )
        }
        dispatchDeliveries(owner)
    }

    private fun clearRuntimeState() {
        eventSink = null
        sessionTokens.values.forEach { it.retire() }
        sessionTokens.clear()
        sessionTargets.clear()
        activeSessions.clear()
        maximumLengths.clear()
        subscriptions.clear()
        supportedModes = emptyMap()
        clearEventDeliveriesLocked()
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

    private data class EventDelivery(
        val generation: Long,
        val permit: PeripheralRequestAdmission.Permit? = null,
        val callback: () -> Unit,
    )

    private sealed interface SessionDelivery {
        fun deliver()

        class Opened(
            private val sink: PeripheralBackendEventSink,
            private val token: BackendSessionToken,
            private val maximumUpdateValueLength: Int,
        ) : SessionDelivery {
            override fun deliver() {
                sink.onSessionOpened(token, maximumUpdateValueLength)
            }
        }

        class MaximumChanged(
            private val sink: PeripheralBackendEventSink,
            private val token: BackendSessionToken,
            private val maximumUpdateValueLength: Int,
        ) : SessionDelivery {
            override fun deliver() {
                sink.onMaximumUpdateValueLengthChanged(
                    token,
                    maximumUpdateValueLength,
                )
            }
        }
    }

    private class SubscriptionDelivery(
        private val sessionDelivery: SessionDelivery?,
        private val sink: PeripheralBackendEventSink,
        private val token: BackendSessionToken,
        private val subscriptions: Set<GattCharacteristicId>,
    ) {
        fun deliver() {
            sessionDelivery?.deliver()
            sink.onSubscriptionsChanged(token, subscriptions)
        }
    }
}

internal class AppleGattResponseException(
    sessionId: PeripheralSessionId,
    requestToken: AppleRequestToken,
) : IllegalStateException(
    "Core Bluetooth rejected GATT response for session $sessionId and token ${requestToken.value}",
)

internal class AppleNotificationValueTooLongException(
    valueSize: Int,
    maximumSize: Int,
) : IllegalArgumentException(
    "Apple notification value has $valueSize bytes, maximum for this central is $maximumSize",
)

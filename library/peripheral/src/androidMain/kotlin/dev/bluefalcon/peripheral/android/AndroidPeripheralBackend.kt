package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothGatt
import dev.bluefalcon.core.BluetoothPermissionException
import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.PeripheralUnsupportedException
import dev.bluefalcon.peripheral.internal.PeripheralRequestAdmission
import dev.bluefalcon.peripheral.internal.PeripheralResourceOverflowException
import dev.bluefalcon.peripheral.internal.PeripheralBackend
import dev.bluefalcon.peripheral.internal.BackendSessionToken
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorReadRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorWriteRequest
import dev.bluefalcon.peripheral.internal.BackendExecuteWriteRequest
import dev.bluefalcon.peripheral.internal.BackendGattResponder
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.TimeMark
import kotlin.time.Duration.Companion.seconds

internal class AndroidPeripheralBackend(
    private val stack: AndroidBluetoothStack,
    private val logger: Logger?,
    private val operationTimeout: Duration = 10.seconds,
    private val allowAdvertisingWithoutGattServer: Boolean = false,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val watchdogDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : PeripheralBackend {
    private val MaximumTrackedSessions = 256
    private val lock = Any()
    private val platformOperationMutex = Mutex()
    private var state: BackendState = BackendState.Stopped
    private var generation = 0L
    private var eventSink: PeripheralBackendEventSink? = null
    private var startupOperationIdle: CompletableDeferred<Unit>? = null
    private val connectedSessions = mutableSetOf<PeripheralSessionId>()
    private val maximumUpdateLengths = mutableMapOf<PeripheralSessionId, Int>()
    private val subscriptions =
        mutableMapOf<PeripheralSessionId, MutableMap<GattCharacteristicId, NotificationMode>>()
    private val preparedCccdWrites = mutableMapOf<PeripheralSessionId, PreparedCccdWrite>()
    private val sessionTokens = mutableMapOf<PeripheralSessionId, BackendSessionToken>()
    private val sessionTargets = mutableMapOf<PeripheralSessionId, AndroidSessionTarget>()
    // Android reports completion by address only. Keep the exact accepted owner even
    // after disconnect, and never let a replacement submit until that callback arrives.
    // Both the deadline and watchdog belong to the exact accepted operation. A timed-out
    // address-only lane stays quarantined until stop/start retires the native server.
    private val pendingNotifications = mutableMapOf<PeripheralSessionId, PendingNotification>()
    private val startupEvents = ArrayDeque<AndroidGattEvent>()
    private var startupBytes = 0
    private var startupFailure: Throwable? = null
    private var supportedNotificationModes = emptyMap<GattCharacteristicId, Set<NotificationMode>>()
    private val eventDeliveries = ArrayDeque<EventDelivery>()
    private var eventDeliveryOwner = false
    private val deliveryAdmission = PeripheralRequestAdmission()
    private val notificationWatchdogAdmission = PeripheralRequestAdmission(maximumBytes = 0)
    private var resourceFailureGeneration: Long? = null
    private var pendingResourceFailure: Pair<PeripheralBackendEventSink, Throwable>? = null

    private val platformSupported =
        stack.capabilities.localGattServer && stack.capabilities.connectableAdvertising

    override val capabilities: PeripheralCapabilities = PeripheralCapabilities(
        localGattServer = stack.capabilities.localGattServer,
        connectableAdvertising = stack.capabilities.connectableAdvertising,
        multiCentral = platformSupported,
        targetedNotifications = platformSupported,
        notificationReadiness = platformSupported,
        maximumUpdateValueLength = platformSupported,
        forcedDisconnect = platformSupported,
        connectionLifecycleVisibility = platformSupported,
        preparedWrites = platformSupported,
        stateRestoration = false,
    )

    init {
        require(operationTimeout > Duration.ZERO && operationTimeout.isFinite()) {
            "Android peripheral operation timeout must be positive and finite"
        }
    }

    override suspend fun start(
        config: PeripheralConfig,
        eventSink: PeripheralBackendEventSink,
    ) {
        try {
            stack.validateStart()
        } catch (cause: Throwable) {
            throw cause.toPeripheralStartFailure()
        }
        val requiresGattServer =
            config.advertiseConfig.services.isNotEmpty() || !allowAdvertisingWithoutGattServer
        validateCapabilities(requiresGattServer)
        val configuredNotificationModes = notificationModes(config)
        val startGeneration = synchronized(lock) {
            when (state) {
                BackendState.Stopped -> Unit
                BackendState.Closed -> throw PeripheralLifecycleException(
                    "Android peripheral backend is closed",
                )
                else -> throw PeripheralLifecycleException(
                    "Android peripheral backend is not stopped",
                )
            }
            val allocatedGeneration = ++generation
            resourceFailureGeneration = null
            state = BackendState.Starting(allocatedGeneration)
            this.eventSink = eventSink
            startupEvents.clear()
            startupBytes = 0
            startupFailure = null
            supportedNotificationModes = configuredNotificationModes
            allocatedGeneration
        }
        val listener = object : AndroidBluetoothStackListener {
            override fun onEvent(event: AndroidGattEvent) {
                onStackEvent(startGeneration, event)
            }

            override fun onPlatformFailure(cause: Throwable) {
                publishPlatformFailure(startGeneration, cause)
            }
            override fun onResourceOverflow(cause: Throwable) {
                synchronized(lock) {
                    if (state == BackendState.Starting(startGeneration) || state == BackendState.Running(startGeneration)) {
                        eventSink.let { markResourceFailureLocked(startGeneration, it) }
                    }
                }
                dispatchDeliveries(false)
            }
        }

        try {
            if (requiresGattServer) {
                runStartupOperation(startGeneration) {
                    stack.open(listener)
                }
            }
            config.advertiseConfig.services.forEach { service ->
                runStartupOperation(startGeneration) {
                    withTimeout(operationTimeout) {
                        stack.addService(service)
                    }
                }
            }
            runStartupOperation(startGeneration) {
                withTimeout(operationTimeout) {
                    stack.startAdvertising(config.advertiseConfig)
                }
            }

            val published = synchronized(lock) {
                if (state == BackendState.Starting(startGeneration)) {
                    startupFailure?.let { throw it }
                    state = BackendState.Running(startGeneration)
                    while (startupEvents.isNotEmpty()) {
                        val staged = startupEvents.removeFirst()
                        stageEventDeliveryLocked(startGeneration, staged, eventSink)
                    }
                    startupBytes = 0
                    true
                } else {
                    false
                }
            }
            if (published) dispatchDeliveries(true)
            if (!published) {
                throw PeripheralLifecycleException(
                    "Android peripheral start was superseded by shutdown",
                )
            }
        } catch (cause: Throwable) {
            withContext(NonCancellable) {
                rollbackStart(startGeneration)
            }
            throw cause.toPeripheralStartFailure()
        }
    }

    override suspend fun stop() {
        shutdown(terminal = false)
    }

    override suspend fun close() {
        shutdown(terminal = true)
    }

    override suspend fun notify(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult = platformOperationMutex.withLock {
        notifyPlatformSerialized(sessionId, characteristic, value, mode, null)
    }

    override suspend fun notify(token: BackendSessionToken, characteristic: GattCharacteristicId, value: ByteArray, mode: NotificationMode): NotificationResult = platformOperationMutex.withLock {
        notifyPlatformSerialized(token.sessionId, characteristic, value, mode, token)
    }

    private fun notifyPlatformSerialized(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
        expectedToken: BackendSessionToken?,
    ): NotificationResult {
        val request = synchronized(lock) {
            if (sessionId !in connectedSessions || (expectedToken != null && (sessionTokens[sessionId] !== expectedToken.backendToken || !expectedToken.isCurrent())) || sessionTokens[sessionId]?.isCurrent() != true) {
                return NotificationResult.Disconnected
            }
            if (supportedNotificationModes[characteristic]?.contains(mode) != true) {
                return NotificationResult.Unsupported
            }
            if (subscriptions[sessionId]?.get(characteristic) != mode) {
                return NotificationResult.Unsupported
            }
            val maximumLength = maximumUpdateLengths[sessionId]
                ?: return NotificationResult.Disconnected
            if (value.size > maximumLength) {
                return NotificationResult.Failed(
                    AndroidNotificationValueTooLongException(value.size, maximumLength),
                )
            }
            pendingNotifications[sessionId]?.let { pending ->
                if (pending.token !== sessionTokens[sessionId]) {
                    return NotificationResult.Failed(AndroidNotificationCompletionAmbiguousException())
                }
                if (pending.expired || pending.deadline.hasPassedNow()) {
                    pending.expired = true
                    return NotificationResult.Failed(AndroidNotificationCompletionAmbiguousException())
                }
                return NotificationResult.Busy
            }
            if (pendingNotifications.size >= MaximumTrackedSessions) {
                return NotificationResult.Failed(PeripheralLifecycleException("Android notification owner capacity exceeded"))
            }
            val permit = notificationWatchdogAdmission.acquire(0, false)
                ?: return NotificationResult.Failed(PeripheralLifecycleException("Android notification watchdog capacity exceeded"))
            val pending = PendingNotification(requireNotNull(sessionTokens[sessionId]), timeSource.markNow() + operationTimeout, generation, permit)
            pendingNotifications[sessionId] = pending
            Triple(AndroidNotificationRequest(sessionId, characteristic, mode, value), sessionTargets[sessionId], pending)
        }

        scheduleNotificationWatchdog(sessionId, request.third)
        return try {
            when (val result = request.second?.notify(request.first) ?: stack.notify(request.first)) {
                AndroidNotificationStartResult.Accepted -> NotificationResult.Sent
                is AndroidNotificationStartResult.Rejected -> {
                    synchronized(lock) { if (pendingNotifications[sessionId] === request.third) { pendingNotifications.remove(sessionId)?.watchdog?.cancel() } }
                    if (result.cause is CancellationException) throw result.cause
                    NotificationResult.Failed(result.cause)
                }
            }
        } catch (cause: Throwable) {
            synchronized(lock) { if (pendingNotifications[sessionId] === request.third) { pendingNotifications.remove(sessionId)?.watchdog?.cancel() } }
            if (cause is CancellationException) throw cause
            NotificationResult.Failed(cause)
        }
    }

    private fun scheduleNotificationWatchdog(sessionId: PeripheralSessionId, pending: PendingNotification) {
        val watchdog = CoroutineScope(watchdogDispatcher).launch(start = CoroutineStart.LAZY) {
            delay(operationTimeout)
            val owner = synchronized(lock) {
                if (state != BackendState.Running(pending.generation) ||
                    pendingNotifications[sessionId] !== pending
                ) return@synchronized false
                pending.expired = true
                val sink = eventSink ?: return@synchronized false
                val token = pending.token
                if (sessionTokens[sessionId] !== token || !token.isCurrent()) return@synchronized false
                enqueueEventDeliveryLocked(EventDelivery(pending.generation) {
                    sink.onNotificationReady(token)
                })
            }
            dispatchDeliveries(owner)
        }
        // LAZY cancellation before execution never enters the body. Completion
        // includes that path and waits for a started canceled continuation to run.
        watchdog.invokeOnCompletion { pending.permit.delivered() }
        val scheduled = synchronized(lock) {
            if (state == BackendState.Running(pending.generation) &&
                pendingNotifications[sessionId] === pending
            ) {
                pending.watchdog = watchdog
                true
            } else false
        }
        if (scheduled) watchdog.start() else watchdog.cancel()
    }

    override suspend fun disconnect(
        sessionId: PeripheralSessionId,
    ): DisconnectResult = platformOperationMutex.withLock {
        disconnectPlatformSerialized(sessionId, null)
    }

    override suspend fun disconnect(token: BackendSessionToken): DisconnectResult = platformOperationMutex.withLock {
        disconnectPlatformSerialized(token.sessionId, token)
    }

    override suspend fun retireSession(token: BackendSessionToken) = platformOperationMutex.withLock {
        val target = synchronized(lock) {
            if (sessionTokens[token.sessionId] !== token.backendToken) return@withLock
            val owned = sessionTargets[token.sessionId]
            removeSessionStateLocked(token.sessionId)
            owned
        }
        if (target != null) target.retire() else stack.disconnect(token.sessionId)
    }

    private fun disconnectPlatformSerialized(sessionId: PeripheralSessionId, expectedToken: BackendSessionToken?): DisconnectResult {
        val target = synchronized(lock) {
            if (sessionId !in connectedSessions || (expectedToken != null && (sessionTokens[sessionId] !== expectedToken.backendToken || !expectedToken.isCurrent())) || sessionTokens[sessionId]?.isCurrent() != true) {
                return DisconnectResult.AlreadyDisconnected
            }
            sessionTargets[sessionId]
        }

        return try {
            if (target?.disconnect() ?: stack.disconnect(sessionId)) {
                DisconnectResult.Disconnected
            } else {
                DisconnectResult.Failed(AndroidDisconnectException(sessionId))
            }
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            DisconnectResult.Failed(cause)
        }
    }

    private fun validateCapabilities(requiresGattServer: Boolean) {
        if (requiresGattServer && !stack.capabilities.localGattServer) {
            throw PeripheralUnsupportedException("Android local GATT server")
        }
        if (!stack.capabilities.connectableAdvertising) {
            throw PeripheralUnsupportedException("Android connectable advertising")
        }
    }

    private fun Throwable.toPeripheralStartFailure(): Throwable =
        if (this is SecurityException) {
            BluetoothPermissionException().also { it.initCause(this) }
        } else {
            this
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
                put(GattCharacteristicId(Uuid.parse(characteristic.uuid)), modes)
            }
        }
    }

    private suspend fun rollbackStart(startGeneration: Long) {
        val shutdown = synchronized(lock) {
            when (val current = state) {
                is BackendState.Starting ->
                    if (current.generation == startGeneration) {
                        createShutdownLocked(startGeneration, terminal = false)
                    } else {
                        null
                    }

                is BackendState.Running ->
                    if (current.generation == startGeneration) {
                        createShutdownLocked(startGeneration, terminal = false)
                    } else {
                        null
                    }

                is BackendState.ShuttingDown ->
                    current.takeIf { it.generation == startGeneration }

                BackendState.Stopped,
                BackendState.Closed,
                -> null
            }
        } ?: return

        performOrAwaitShutdown(shutdown)
    }

    private suspend fun shutdown(terminal: Boolean) {
        val shutdown = synchronized(lock) {
            when (val current = state) {
                BackendState.Stopped -> {
                    if (terminal) {
                        state = BackendState.Closed
                        eventSink = null
                    }
                    null
                }

                BackendState.Closed -> null

                is BackendState.Starting ->
                    createShutdownLocked(current.generation, terminal)

                is BackendState.Running ->
                    createShutdownLocked(current.generation, terminal)

                is BackendState.ShuttingDown -> {
                    if (terminal && !current.terminal) {
                        current.copy(terminal = true).also { state = it }
                    } else {
                        current
                    }
                }
            }
        } ?: return

        performOrAwaitShutdown(shutdown)
    }

    private fun createShutdownLocked(
        shutdownGeneration: Long,
        terminal: Boolean,
    ): BackendState.ShuttingDown {
        eventSink = null
        clearSessionStateLocked()
        clearEventDeliveriesLocked()
        return BackendState.ShuttingDown(
            generation = shutdownGeneration,
            terminal = terminal,
            completion = CompletableDeferred(),
            ownerClaimed = false,
        ).also { state = it }
    }

    private suspend fun performOrAwaitShutdown(shutdown: BackendState.ShuttingDown) {
        val owner = synchronized(lock) {
            val current = state as? BackendState.ShuttingDown
            if (current == null || current.completion !== shutdown.completion) {
                false
            } else if (!current.ownerClaimed) {
                state = current.copy(ownerClaimed = true)
                true
            } else {
                false
            }
        }

        if (!owner) {
            shutdown.completion.await()
            return
        }

        withContext(NonCancellable) {
            synchronized(lock) { startupOperationIdle }?.await()
            platformOperationMutex.withLock { teardownPlatform() }
            val terminal = synchronized(lock) {
                val current = state as? BackendState.ShuttingDown
                if (current != null && current.completion === shutdown.completion) {
                    state = if (current.terminal) BackendState.Closed else BackendState.Stopped
                    current.terminal
                } else {
                    false
                }
            }
            shutdown.completion.complete(Unit)
            if (terminal) {
                synchronized(lock) { eventSink = null }
            }
        }
    }

    private suspend fun <T> runStartupOperation(
        startGeneration: Long,
        operation: suspend () -> T,
    ): T {
        val idle = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (state != BackendState.Starting(startGeneration)) {
                throw PeripheralLifecycleException(
                    "Android peripheral start was superseded by shutdown",
                )
            }
            check(startupOperationIdle == null) {
                "Android peripheral startup operations must be sequential"
            }
            startupOperationIdle = idle
        }

        try {
            return operation()
        } finally {
            val completion = synchronized(lock) {
                if (startupOperationIdle === idle) {
                    startupOperationIdle = null
                    idle
                } else {
                    null
                }
            }
            completion?.complete(Unit)
        }
    }

    private fun teardownPlatform() {
        runCatching { stack.stopAdvertising() }
            .onFailure { logger?.warn("Failed to stop Android peripheral advertising", it) }
        runCatching { stack.clearServices() }
            .onFailure { logger?.warn("Failed to clear Android peripheral GATT services", it) }
        runCatching { stack.closeGattServer() }
            .onFailure { logger?.warn("Failed to close Android peripheral GATT server", it) }
    }

    private fun onStackEvent(eventGeneration: Long, event: AndroidGattEvent) {
        var shutdownResponse: ShutdownResponse? = null
        val owner = synchronized(lock) {
            when (state) {
                BackendState.Starting(eventGeneration) -> {
                    if (resourceFailureGeneration == eventGeneration) return@synchronized false
                    val bytes = when (event) {
                        is AndroidGattEvent.CharacteristicWrite -> event.payloadBytes.toInt()
                        is AndroidGattEvent.DescriptorWrite -> event.payloadBytes.toInt()
                        else -> 0
                    }
                    if (startupEvents.size >= 256 || bytes > 65536 - startupBytes) {
                        startupFailure = PeripheralLifecycleException("Android peripheral startup callback capacity exceeded")
                    } else if (startupFailure == null) {
                        startupEvents.addLast(event)
                        startupBytes += bytes
                    }
                    false
                }
                BackendState.Running(eventGeneration) -> {
                    if (resourceFailureGeneration == eventGeneration) false
                    else eventSink?.let { stageEventDeliveryLocked(eventGeneration, event, it) } ?: false
                }

                is BackendState.ShuttingDown -> {
                    val shuttingDown = state as BackendState.ShuttingDown
                    if (shuttingDown.generation == eventGeneration) {
                        shutdownResponse = event.shutdownFailureResponse()?.let { ShutdownResponse(eventGeneration, it, event.target) }
                    }
                    false
                }

                else -> false
            }
        }
        shutdownResponse?.let(::sendShutdownFailureResponse)
        dispatchDeliveries(owner)
    }

    private fun AndroidGattEvent.shutdownFailureResponse(): AndroidGattResponse? {
        val request = when (this) {
            is AndroidGattEvent.CharacteristicRead -> requestId to offset
            is AndroidGattEvent.CharacteristicWrite ->
                if (responseNeeded || preparedWrite) requestId to offset else null
            is AndroidGattEvent.DescriptorRead -> requestId to offset
            is AndroidGattEvent.DescriptorWrite ->
                if (responseNeeded || preparedWrite) requestId to offset else null
            is AndroidGattEvent.ExecuteWrite -> requestId to 0
            else -> null
        } ?: return null
        return AndroidGattResponse(
            sessionId = sessionId,
            requestId = request.first,
            status = GattResponseStatus.UnlikelyError,
            offset = request.second,
            value = null,
        )
    }

    private class ShutdownResponse(
        val generation: Long,
        val response: AndroidGattResponse,
        val target: AndroidSessionTarget?,
    )

    private fun sendShutdownFailureResponse(owned: ShutdownResponse) {
        runCatching {
            if (owned.target != null) owned.target.sendResponse(owned.response)
            else synchronized(lock) {
                // Compatibility stacks have no native target. Serialize the final
                // generation check with shutdown completion and replacement startup.
                if ((state as? BackendState.ShuttingDown)?.generation == owned.generation) {
                    stack.sendResponse(owned.response)
                } else false
            }
        }
            .onFailure {
                logger?.warn("Failed to reject Android GATT request during shutdown", it)
            }
    }

    private fun eventPayloadBytes(event: AndroidGattEvent): Long = when (event) {
        is AndroidGattEvent.CharacteristicWrite -> event.payloadBytes
        is AndroidGattEvent.DescriptorWrite -> event.payloadBytes
        else -> 0
    }

    private fun stageEventDeliveryLocked(generation: Long, event: AndroidGattEvent, sink: PeripheralBackendEventSink): Boolean {
        val permit = deliveryAdmission.acquire(eventPayloadBytes(event), false)
        if (permit == null) {
            markResourceFailureLocked(generation, sink)
            return false
        }
        val callback = try { handleEventLocked(generation, event, sink) }
        catch (cause: Throwable) { permit.delivered(); throw cause }
        if (callback == null) { permit.delivered(); return false }
        return enqueueEventDeliveryLocked(EventDelivery(generation, permit, callback))
    }

    private fun markResourceFailureLocked(generation: Long, sink: PeripheralBackendEventSink) {
        if (resourceFailureGeneration == generation) return
        resourceFailureGeneration = generation
        sessionTokens.values.forEach { it.retire() }
        clearEventDeliveriesLocked()
        pendingResourceFailure = sink to PeripheralResourceOverflowException()
    }

    private fun clearEventDeliveriesLocked() {
        eventDeliveries.forEach { it.permit?.delivered() }
        eventDeliveries.clear()
    }

    private fun dispatchDeliveries(owner: Boolean) {
        val failure = synchronized(lock) { pendingResourceFailure.also { pendingResourceFailure = null } }
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
            val delivery = synchronized(lock) {
                eventDeliveries.removeFirstOrNull().also { next ->
                    if (next == null) eventDeliveryOwner = false
                }
            } ?: return
            val current = synchronized(lock) {
                state == BackendState.Running(delivery.generation) && resourceFailureGeneration != delivery.generation
            }
            try {
                if (current) runCatching(delivery.callback)
                    .onFailure { logger?.warn("Android peripheral event delivery failed", it) }
            } finally { delivery.permit?.delivered() }
        }
    }

    private fun handleEventLocked(
        eventGeneration: Long,
        event: AndroidGattEvent,
        sink: PeripheralBackendEventSink,
    ): (() -> Unit)? {
        if (event !is AndroidGattEvent.Connected && event !is AndroidGattEvent.NotificationSent && event.target != null && sessionTargets[event.sessionId] !== event.target) return null
        return when (event) {
            is AndroidGattEvent.Connected -> {
                if (event.sessionId !in connectedSessions && connectedSessions.size >= MaximumTrackedSessions) {
                    val delivery = {
                        event.target?.disconnect() ?: stack.disconnect(event.sessionId)
                        sink.onPlatformFailure(PeripheralLifecycleException("Android connection owner capacity exceeded"))
                    }
                    delivery
                } else if (!connectedSessions.add(event.sessionId)) {
                    null
                } else {
                    val target = event.target
                    lateinit var token: BackendSessionToken
                    token = BackendSessionToken(event.sessionId) {
                        synchronized(lock) { state == BackendState.Running(eventGeneration) && sessionTokens[event.sessionId] === token && (target?.isCurrent() != false) }
                    }
                    sessionTokens[event.sessionId] = token
                    if (target != null) sessionTargets[event.sessionId] = target
                    maximumUpdateLengths[event.sessionId] = DefaultMaximumUpdateValueLength
                    subscriptions[event.sessionId] = mutableMapOf()
                    val delivery = {
                        sink.onSessionOpened(token, DefaultMaximumUpdateValueLength)
                    }
                    delivery
                }
            }

            is AndroidGattEvent.MtuChanged -> {
                if (event.sessionId !in connectedSessions) {
                    null
                } else {
                    val maximumUpdateValueLength =
                        (event.mtu - AttHeaderLength).coerceAtLeast(0)
                    maximumUpdateLengths[event.sessionId] = maximumUpdateValueLength
                    val token = sessionTokens[event.sessionId] ?: return null
                    val delivery = {
                        sink.onMaximumUpdateValueLengthChanged(
                            token,
                            maximumUpdateValueLength,
                        )
                    }
                    delivery
                }
            }

            is AndroidGattEvent.Disconnected -> {
                val token = sessionTokens[event.sessionId] ?: return null
                if (!removeSessionStateLocked(event.sessionId)) {
                    null
                } else {
                    val cause = if (event.status != BluetoothGatt.GATT_SUCCESS) {
                        AndroidConnectionStateException(event.sessionId, event.status)
                    } else null
                    val delivery = { sink.onSessionClosed(token, cause) }
                    delivery
                }
            }

            is AndroidGattEvent.CharacteristicRead -> {
                if (event.sessionId !in connectedSessions) {
                    null
                } else {
                    val request = BackendCharacteristicReadRequest(
                        sessionId = event.sessionId,
                        serviceId = event.serviceId,
                        characteristicId = event.characteristicId,
                        offset = event.offset,
                        responder = createGattResponder(
                            eventGeneration = eventGeneration,
                            sessionId = event.sessionId,
                            requestId = event.requestId,
                            offset = event.offset,
                        ),
                    )
                    val token = sessionTokens[event.sessionId] ?: return null
                    val delivery = { sink.onRequest(token, request) }
                    delivery
                }
            }

            is AndroidGattEvent.CharacteristicWrite -> {
                if (event.sessionId !in connectedSessions) {
                    null
                } else {
                    val responder = if (event.responseNeeded || event.preparedWrite) {
                        createGattResponder(
                            eventGeneration = eventGeneration,
                            sessionId = event.sessionId,
                            requestId = event.requestId,
                            offset = event.offset,
                        )
                    } else {
                        null
                    }
                    val request = BackendCharacteristicWriteRequest(
                        sessionId = event.sessionId,
                        serviceId = event.serviceId,
                        characteristicId = event.characteristicId,
                        offset = event.offset,
                        value = event.value,
                        preparedWrite = event.preparedWrite,
                        responder = responder,
                        requestId = if (event.responseNeeded) event.requestId else -1,
                    )
                    val token = sessionTokens[event.sessionId] ?: return null
                    val delivery = { sink.onRequest(token, request) }
                    delivery
                }
            }

            is AndroidGattEvent.DescriptorRead -> {
                if (event.sessionId !in connectedSessions) {
                    null
                } else {
                    val request = BackendDescriptorReadRequest(
                        sessionId = event.sessionId,
                        serviceId = event.serviceId,
                        characteristicId = event.characteristicId,
                        descriptorId = event.descriptorId,
                        offset = event.offset,
                        responder = createGattResponder(
                            eventGeneration = eventGeneration,
                            sessionId = event.sessionId,
                            requestId = event.requestId,
                            offset = event.offset,
                        ),
                    )
                    val token = sessionTokens[event.sessionId] ?: return null
                    val delivery = { sink.onRequest(token, request) }
                    delivery
                }
            }

            is AndroidGattEvent.DescriptorWrite -> {
                if (event.sessionId !in connectedSessions) {
                    null
                } else {
                    val cccdWrite = event.takeIf {
                        it.descriptorId.uuid.toString() == CccdUuid
                    }
                    if (cccdWrite?.preparedWrite == true &&
                        (event.offset !in 0 until CccdValueLength || event.payloadBytes == 0L || event.payloadBytes > CccdValueLength - event.offset)) {
                        preparedCccdWrites.remove(event.sessionId)
                        val reject = createGattResponder(eventGeneration, event.sessionId, event.requestId, event.offset)
                        val status = if (event.offset !in 0 until CccdValueLength) GattResponseStatus.InvalidOffset else GattResponseStatus.InvalidAttributeValueLength
                        return { reject.respond(status, null) }
                    }
                    val responder = if (event.responseNeeded || event.preparedWrite) {
                        createGattResponder(
                            eventGeneration = eventGeneration,
                            sessionId = event.sessionId,
                            requestId = event.requestId,
                            offset = event.offset,
                            onResponse = cccdWrite?.let { write ->
                                { status ->
                                    when {
                                        write.preparedWrite && status == GattResponseStatus.Success ->
                                            stagePreparedCccdWrite(eventGeneration, write)

                                        write.preparedWrite ->
                                            discardPreparedCccdWrite(eventGeneration, write.sessionId)

                                        !write.preparedWrite && status == GattResponseStatus.Success ->
                                            commitCccdWrite(
                                                eventGeneration = eventGeneration,
                                                sessionId = write.sessionId,
                                                characteristicId = write.characteristicId,
                                                value = write.value,
                                            )
                                    }
                                }
                            },
                        )
                    } else {
                        null
                    }
                    val request = BackendDescriptorWriteRequest(
                        sessionId = event.sessionId,
                        serviceId = event.serviceId,
                        characteristicId = event.characteristicId,
                        descriptorId = event.descriptorId,
                        offset = event.offset,
                        value = event.value,
                        preparedWrite = event.preparedWrite,
                        responder = responder,
                    )
                    val subscriptions = if (cccdWrite != null && responder == null) {
                        commitCccdWriteLocked(
                            sessionId = cccdWrite.sessionId,
                            characteristicId = cccdWrite.characteristicId,
                            value = cccdWrite.value,
                        )
                    } else {
                        null
                    }
                    val token = sessionTokens[event.sessionId] ?: return null
                    val delivery = {
                        sink.onRequest(token, request)
                        subscriptions?.let { updated ->
                            sink.onSubscriptionsChanged(token, updated)
                        }
                        Unit
                    }
                    delivery
                }
            }

            is AndroidGattEvent.ExecuteWrite -> {
                if (event.sessionId !in connectedSessions) {
                    null
                } else {
                    val request = BackendExecuteWriteRequest(
                        sessionId = event.sessionId,
                        execute = event.execute,
                        responder = createGattResponder(
                            eventGeneration = eventGeneration,
                            sessionId = event.sessionId,
                            requestId = event.requestId,
                            offset = 0,
                            onResponse = { status ->
                                completePreparedCccdWrite(
                                    eventGeneration = eventGeneration,
                                    sessionId = event.sessionId,
                                    execute = event.execute,
                                    status = status,
                                )
                            },
                        ),
                    )
                    val token = sessionTokens[event.sessionId] ?: return null
                    val delivery = { sink.onRequest(token, request) }
                    delivery
                }
            }

            is AndroidGattEvent.NotificationSent -> {
                val pending = pendingNotifications[event.sessionId] ?: return null
                if (pending.expired || pending.deadline.hasPassedNow()) {
                    pending.expired = true
                    return null
                }
                pendingNotifications.remove(event.sessionId)
                pending.watchdog?.cancel()
                val token = pending.token
                if (sessionTokens[event.sessionId] !== token || !token.isCurrent()) null
                else {
                    val delivery = {
                        sink.onNotificationReady(token)
                        if (event.status != BluetoothGatt.GATT_SUCCESS) sink.onPlatformFailure(token, AndroidNotificationCallbackException(event.sessionId, event.status))
                    }
                    delivery
                }
            }
        }
    }

    private fun stagePreparedCccdWrite(
        eventGeneration: Long,
        event: AndroidGattEvent.DescriptorWrite,
    ) {
        synchronized(lock) {
            if (state != BackendState.Running(eventGeneration) ||
                event.sessionId !in connectedSessions
            ) {
                return
            }
            val existing = preparedCccdWrites[event.sessionId]
            val prepared = if (existing?.matches(event) == true) {
                existing
            } else {
                PreparedCccdWrite(
                    serviceId = event.serviceId,
                    characteristicId = event.characteristicId,
                    descriptorId = event.descriptorId,
                ).also { preparedCccdWrites[event.sessionId] = it }
            }
            prepared.fragments[event.offset] = event.value
        }
    }

    private fun discardPreparedCccdWrite(
        eventGeneration: Long,
        sessionId: PeripheralSessionId,
    ) {
        synchronized(lock) {
            if (state == BackendState.Running(eventGeneration)) {
                preparedCccdWrites.remove(sessionId)
            }
        }
    }

    private fun completePreparedCccdWrite(
        eventGeneration: Long,
        sessionId: PeripheralSessionId,
        execute: Boolean,
        status: GattResponseStatus,
    ) {
        val owner = synchronized(lock) {
            val prepared = preparedCccdWrites.remove(sessionId) ?: return@synchronized false
            if (state != BackendState.Running(eventGeneration) ||
                sessionId !in connectedSessions ||
                !execute ||
                status != GattResponseStatus.Success
            ) {
                return@synchronized false
            }
            val value = prepared.assembleValue() ?: return@synchronized false
            val updated = commitCccdWriteLocked(
                sessionId = sessionId,
                characteristicId = prepared.characteristicId,
                value = value,
            ) ?: return@synchronized false
            val sink = eventSink ?: return@synchronized false
            val token = sessionTokens[sessionId] ?: return@synchronized false
            enqueueEventDeliveryLocked(
                EventDelivery(eventGeneration) {
                    sink.onSubscriptionsChanged(token, updated)
                },
            )
        }
        dispatchDeliveries(owner)
    }

    private fun commitCccdWrite(
        eventGeneration: Long,
        sessionId: PeripheralSessionId,
        characteristicId: GattCharacteristicId,
        value: ByteArray,
    ) {
        val owner = synchronized(lock) {
            if (state != BackendState.Running(eventGeneration)) return@synchronized false
            val updated = commitCccdWriteLocked(sessionId, characteristicId, value)
                ?: return@synchronized false
            val sink = eventSink ?: return@synchronized false
            val token = sessionTokens[sessionId] ?: return@synchronized false
            enqueueEventDeliveryLocked(
                EventDelivery(eventGeneration) {
                    sink.onSubscriptionsChanged(token, updated)
                },
            )
        }
        dispatchDeliveries(owner)
    }

    private fun commitCccdWriteLocked(
        sessionId: PeripheralSessionId,
        characteristicId: GattCharacteristicId,
        value: ByteArray,
    ): Set<GattCharacteristicId>? {
        if (sessionId !in connectedSessions) return null
        val decoded = decodeCccdValue(value) ?: return null
        val sessionSubscriptions = subscriptions[sessionId] ?: return null
        val changed = when (decoded) {
            CccdValue.Disabled -> sessionSubscriptions.remove(characteristicId) != null
            is CccdValue.Enabled ->
                sessionSubscriptions.put(characteristicId, decoded.mode) != decoded.mode
        }
        return if (changed) sessionSubscriptions.keys.toSet() else null
    }

    private fun decodeCccdValue(value: ByteArray): CccdValue? = when {
        value.contentEquals(DisableCccdValue) -> CccdValue.Disabled
        value.contentEquals(NotificationCccdValue) ->
            CccdValue.Enabled(NotificationMode.Notification)

        value.contentEquals(IndicationCccdValue) ->
            CccdValue.Enabled(NotificationMode.Indication)

        else -> null
    }

    private fun createGattResponder(
        eventGeneration: Long,
        sessionId: PeripheralSessionId,
        requestId: Int,
        offset: Int,
        onResponse: ((GattResponseStatus) -> Unit)? = null,
    ): BackendGattResponder {
        val token = sessionTokens[sessionId]
        val target = sessionTargets[sessionId]
        val responseLock = Any()
        var pending = true
        return BackendGattResponder { status, value ->
            val accepted = synchronized(responseLock) {
                if (!pending) {
                    false
                } else {
                    pending = false
                    true
                }
            }
            if (!accepted) return@BackendGattResponder

            if (token == null || !token.isCurrent()) return@BackendGattResponder
            val sent = try {
                val response =
                    AndroidGattResponse(
                        sessionId = sessionId,
                        requestId = requestId,
                        status = status,
                        offset = offset,
                        value = value,
                    )
                synchronized(lock) {
                    if (sessionTokens[sessionId] !== token || !token.isCurrent()) return@BackendGattResponder
                    target?.sendResponse(response) ?: stack.sendResponse(response)
                }
            } catch (cause: Throwable) {
                publishPlatformFailure(eventGeneration, cause, token)
                return@BackendGattResponder
            }
            if (sent) {
                synchronized(lock) {
                    if (sessionTokens[sessionId] === token && token.isCurrent()) onResponse?.invoke(status)
                }
            } else {
                publishPlatformFailure(
                    eventGeneration,
                    AndroidGattResponseException(requestId),
                    token,
                )
            }
        }
    }

    private fun publishPlatformFailure(eventGeneration: Long, cause: Throwable, token: BackendSessionToken? = null) {
        val owner = synchronized(lock) {
            val sink = eventSink
            if (token != null && (sessionTokens[token.sessionId] !== token || !token.isCurrent())) return
            if (state == BackendState.Starting(eventGeneration)) {
                startupFailure = cause
                false
            } else if (state != BackendState.Running(eventGeneration) || sink == null) {
                false
            } else {
                enqueueEventDeliveryLocked(
                    EventDelivery(eventGeneration) {
                        if (token == null) sink.onPlatformFailure(cause)
                        else if (token.isCurrent()) sink.onPlatformFailure(token, cause)
                    },
                )
            }
        }
        dispatchDeliveries(owner)
    }

    private fun removeSessionStateLocked(sessionId: PeripheralSessionId): Boolean {
        if (!connectedSessions.remove(sessionId)) return false
        maximumUpdateLengths.remove(sessionId)
        subscriptions.remove(sessionId)
        preparedCccdWrites.remove(sessionId)
        sessionTokens.remove(sessionId)?.retire()
        sessionTargets.remove(sessionId)
        // Retain an accepted notification until its ambiguous address-only completion is consumed.
        return true
    }

    private fun clearSessionStateLocked() {
        sessionTokens.values.forEach { it.retire() }
        sessionTokens.clear()
        sessionTargets.clear()
        startupEvents.clear()
        startupBytes = 0
        startupFailure = null
        connectedSessions.clear()
        maximumUpdateLengths.clear()
        subscriptions.clear()
        preparedCccdWrites.clear()
        pendingNotifications.values.forEach { it.watchdog?.cancel() }
        pendingNotifications.clear()
        supportedNotificationModes = emptyMap()
    }

    private class PendingNotification(
        val token: BackendSessionToken,
        val deadline: TimeMark,
        val generation: Long,
        val permit: PeripheralRequestAdmission.Permit,
        var watchdog: Job? = null,
        var expired: Boolean = false,
    )

    private sealed interface BackendState {
        data object Stopped : BackendState
        data class Starting(val generation: Long) : BackendState
        data class Running(val generation: Long) : BackendState
        data class ShuttingDown(
            val generation: Long,
            val terminal: Boolean,
            val completion: CompletableDeferred<Unit>,
            val ownerClaimed: Boolean,
        ) : BackendState
        data object Closed : BackendState
    }

    private data class EventDelivery(
        val generation: Long,
        val permit: PeripheralRequestAdmission.Permit? = null,
        val callback: () -> Unit,
    )

    private data class PreparedCccdWrite(
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val descriptorId: GattDescriptorId,
        val fragments: MutableMap<Int, ByteArray> = mutableMapOf(),
    ) {
        fun matches(event: AndroidGattEvent.DescriptorWrite): Boolean =
            serviceId == event.serviceId &&
                characteristicId == event.characteristicId &&
                descriptorId == event.descriptorId

        fun assembleValue(): ByteArray? {
            val value = ByteArray(CccdValueLength)
            val populated = BooleanArray(CccdValueLength)
            for ((offset, fragment) in fragments) {
                if (offset < 0 || offset + fragment.size > CccdValueLength) return null
                for (index in fragment.indices) {
                    val target = offset + index
                    if (populated[target]) return null
                    value[target] = fragment[index]
                    populated[target] = true
                }
            }
            return value.takeIf { populated.all { it } }
        }
    }

    private sealed interface CccdValue {
        data object Disabled : CccdValue
        data class Enabled(val mode: NotificationMode) : CccdValue
    }

    private companion object {
        const val DefaultMaximumUpdateValueLength = 20
        const val AttHeaderLength = 3
        const val CccdUuid = "00002902-0000-1000-8000-00805f9b34fb"
        const val CccdValueLength = 2
        val DisableCccdValue = byteArrayOf(0, 0)
        val NotificationCccdValue = byteArrayOf(1, 0)
        val IndicationCccdValue = byteArrayOf(2, 0)
    }
}

internal class AndroidGattResponseException(requestId: Int) : IllegalStateException(
    "Android GATT server rejected response for request $requestId",
)

internal class AndroidNotificationValueTooLongException(
    valueSize: Int,
    maximumSize: Int,
) : IllegalArgumentException(
    "Android notification value size $valueSize exceeds the negotiated limit $maximumSize",
)

internal class AndroidNotificationCallbackException(
    sessionId: PeripheralSessionId,
    status: Int,
) : IllegalStateException(
    "Android notification callback failed for session ${sessionId.value} with status $status",
)

internal class AndroidDisconnectException(sessionId: PeripheralSessionId) : IllegalStateException(
    "Android rejected disconnect for session ${sessionId.value}",
)

internal class AndroidConnectionStateException(
    val sessionId: PeripheralSessionId,
    val status: Int,
) : IllegalStateException(
    "Android GATT connection failed for session ${sessionId.value} with status $status",
)

internal class AndroidNotificationCompletionAmbiguousException : IllegalStateException(
    "Android notification completion deadline elapsed; stop and start the peripheral to retire the ambiguous native callback lane",
)

package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothGatt
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
import dev.bluefalcon.peripheral.internal.PeripheralBackend
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorReadRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorWriteRequest
import dev.bluefalcon.peripheral.internal.BackendExecuteWriteRequest
import dev.bluefalcon.peripheral.internal.BackendGattResponder
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class AndroidPeripheralBackend(
    private val stack: AndroidBluetoothStack,
    private val logger: Logger?,
    private val operationTimeout: Duration = 10.seconds,
) : PeripheralBackend {
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
    private val pendingNotifications = mutableSetOf<PeripheralSessionId>()
    private var supportedNotificationModes = emptyMap<GattCharacteristicId, Set<NotificationMode>>()
    private val eventDeliveries = ArrayDeque<EventDelivery>()
    private var eventDeliveryOwner = false

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
        validateCapabilities()
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
            state = BackendState.Starting(allocatedGeneration)
            this.eventSink = eventSink
            supportedNotificationModes = configuredNotificationModes
            allocatedGeneration
        }
        val listener = AndroidBluetoothStackListener { event ->
            onStackEvent(startGeneration, event)
        }

        try {
            runStartupOperation(startGeneration) {
                stack.open(listener)
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
                    state = BackendState.Running(startGeneration)
                    true
                } else {
                    false
                }
            }
            if (!published) {
                throw PeripheralLifecycleException(
                    "Android peripheral start was superseded by shutdown",
                )
            }
        } catch (cause: Throwable) {
            withContext(NonCancellable) {
                rollbackStart(startGeneration)
            }
            throw cause
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
        notifyPlatformSerialized(sessionId, characteristic, value, mode)
    }

    private fun notifyPlatformSerialized(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult {
        val request = synchronized(lock) {
            if (sessionId !in connectedSessions) {
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
            if (!pendingNotifications.add(sessionId)) {
                return NotificationResult.Busy
            }
            AndroidNotificationRequest(sessionId, characteristic, mode, value)
        }

        return try {
            when (val result = stack.notify(request)) {
                AndroidNotificationStartResult.Accepted -> NotificationResult.Sent
                is AndroidNotificationStartResult.Rejected -> {
                    synchronized(lock) { pendingNotifications.remove(sessionId) }
                    if (result.cause is CancellationException) throw result.cause
                    NotificationResult.Failed(result.cause)
                }
            }
        } catch (cause: Throwable) {
            synchronized(lock) { pendingNotifications.remove(sessionId) }
            if (cause is CancellationException) throw cause
            NotificationResult.Failed(cause)
        }
    }

    override suspend fun disconnect(
        sessionId: PeripheralSessionId,
    ): DisconnectResult = platformOperationMutex.withLock {
        disconnectPlatformSerialized(sessionId)
    }

    private fun disconnectPlatformSerialized(sessionId: PeripheralSessionId): DisconnectResult {
        synchronized(lock) {
            if (sessionId !in connectedSessions) {
                return DisconnectResult.AlreadyDisconnected
            }
        }

        return try {
            if (stack.disconnect(sessionId)) {
                DisconnectResult.Disconnected
            } else {
                DisconnectResult.Failed(AndroidDisconnectException(sessionId))
            }
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            DisconnectResult.Failed(cause)
        }
    }

    private fun validateCapabilities() {
        if (!stack.capabilities.localGattServer) {
            throw PeripheralUnsupportedException("Android local GATT server")
        }
        if (!stack.capabilities.connectableAdvertising) {
            throw PeripheralUnsupportedException("Android connectable advertising")
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
        eventDeliveries.clear()
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
        runCatching { stack.closeGattServer() }
            .onFailure { logger?.warn("Failed to close Android peripheral GATT server", it) }
    }

    private fun onStackEvent(eventGeneration: Long, event: AndroidGattEvent) {
        val owner = synchronized(lock) {
            if (state != BackendState.Running(eventGeneration)) {
                false
            } else {
                val delivery = eventSink?.let { sink ->
                    handleEventLocked(eventGeneration, event, sink)
                }
                if (delivery == null) {
                    false
                } else {
                    enqueueEventDeliveryLocked(EventDelivery(eventGeneration, delivery))
                }
            }
        }
        if (owner) drainEventDeliveries()
    }

    private fun enqueueEventDeliveryLocked(delivery: EventDelivery): Boolean {
        eventDeliveries.addLast(delivery)
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
                state == BackendState.Running(delivery.generation)
            }
            if (current) {
                runCatching(delivery.callback)
                    .onFailure { logger?.warn("Android peripheral event delivery failed", it) }
            }
        }
    }

    private fun handleEventLocked(
        eventGeneration: Long,
        event: AndroidGattEvent,
        sink: PeripheralBackendEventSink,
    ): (() -> Unit)? = when (event) {
        is AndroidGattEvent.Connected -> {
            if (!connectedSessions.add(event.sessionId)) {
                null
            } else {
                maximumUpdateLengths[event.sessionId] = DefaultMaximumUpdateValueLength
                subscriptions[event.sessionId] = mutableMapOf()
                val delivery = {
                    sink.onSessionOpened(event.sessionId, DefaultMaximumUpdateValueLength)
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
                val delivery = {
                    sink.onMaximumUpdateValueLengthChanged(
                        event.sessionId,
                        maximumUpdateValueLength,
                    )
                }
                delivery
            }
        }

        is AndroidGattEvent.Disconnected -> {
            if (!removeSessionStateLocked(event.sessionId)) {
                null
            } else {
                val delivery = { sink.onSessionClosed(event.sessionId) }
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
                val delivery = { sink.onRequest(request) }
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
                )
                val delivery = { sink.onRequest(request) }
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
                val delivery = { sink.onRequest(request) }
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
                val delivery = {
                    sink.onRequest(request)
                    subscriptions?.let { updated ->
                        sink.onSubscriptionsChanged(event.sessionId, updated)
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
                val delivery = { sink.onRequest(request) }
                delivery
            }
        }

        is AndroidGattEvent.NotificationSent -> {
            pendingNotifications.remove(event.sessionId)
            val delivery = {
                sink.onNotificationReady(NotificationReadiness.Session(event.sessionId))
                if (event.status != BluetoothGatt.GATT_SUCCESS) {
                    sink.onPlatformFailure(
                        AndroidNotificationCallbackException(event.sessionId, event.status),
                    )
                }
            }
            delivery
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
            enqueueEventDeliveryLocked(
                EventDelivery(eventGeneration) {
                    sink.onSubscriptionsChanged(sessionId, updated)
                },
            )
        }
        if (owner) drainEventDeliveries()
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
            enqueueEventDeliveryLocked(
                EventDelivery(eventGeneration) {
                    sink.onSubscriptionsChanged(sessionId, updated)
                },
            )
        }
        if (owner) drainEventDeliveries()
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

            val sent = try {
                stack.sendResponse(
                    AndroidGattResponse(
                        sessionId = sessionId,
                        requestId = requestId,
                        status = status,
                        offset = offset,
                        value = value,
                    ),
                )
            } catch (cause: Throwable) {
                publishPlatformFailure(eventGeneration, cause)
                return@BackendGattResponder
            }
            onResponse?.invoke(status)
            if (!sent) {
                publishPlatformFailure(
                    eventGeneration,
                    AndroidGattResponseException(requestId),
                )
            }
        }
    }

    private fun publishPlatformFailure(eventGeneration: Long, cause: Throwable) {
        val owner = synchronized(lock) {
            val sink = eventSink
            if (state != BackendState.Running(eventGeneration) || sink == null) {
                false
            } else {
                enqueueEventDeliveryLocked(
                    EventDelivery(eventGeneration) { sink.onPlatformFailure(cause) },
                )
            }
        }
        if (owner) drainEventDeliveries()
    }

    private fun removeSessionStateLocked(sessionId: PeripheralSessionId): Boolean {
        if (!connectedSessions.remove(sessionId)) return false
        maximumUpdateLengths.remove(sessionId)
        subscriptions.remove(sessionId)
        preparedCccdWrites.remove(sessionId)
        pendingNotifications.remove(sessionId)
        return true
    }

    private fun clearSessionStateLocked() {
        connectedSessions.clear()
        maximumUpdateLengths.clear()
        subscriptions.clear()
        preparedCccdWrites.clear()
        pendingNotifications.clear()
        supportedNotificationModes = emptyMap()
    }

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

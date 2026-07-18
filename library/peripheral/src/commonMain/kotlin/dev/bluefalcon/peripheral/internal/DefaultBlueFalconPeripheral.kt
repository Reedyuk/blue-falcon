package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicReadRequest
import dev.bluefalcon.peripheral.GattCharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorReadRequest
import dev.bluefalcon.peripheral.GattDescriptorWriteRequest
import dev.bluefalcon.peripheral.GattExecuteWriteRequest
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralEvent
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralManagerState
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

internal class DefaultBlueFalconPeripheral(
    private val backend: PeripheralBackend,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    requestCapacity: Int = DefaultBufferCapacity,
    eventCapacity: Int = DefaultBufferCapacity,
) : BlueFalconPeripheral {

    init {
        require(requestCapacity > 0) { "Request capacity must be positive" }
        require(eventCapacity > 0) { "Event capacity must be positive" }
    }

    private val lifecycleMutex = Mutex()
    private val sessionMutex = Mutex()
    private val pendingResponseMutex = Mutex()
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(
        coroutineContext.minusKey(Job) + managerJob,
    )
    private val sessionRegistry = mutableMapOf<PeripheralSessionId, DefaultPeripheralSession>()
    private val pendingResponses =
        mutableMapOf<PeripheralSessionId, MutableSet<DefaultGattResponseHandle>>()
    private var nextGeneration = 0L
    private var activeGeneration = NoGeneration
    private var activeConfig: PeripheralConfig? = null

    private val mutableState = MutableStateFlow<PeripheralManagerState>(
        PeripheralManagerState.Stopped,
    )
    override val state: StateFlow<PeripheralManagerState> = mutableState.asStateFlow()

    override val capabilities: PeripheralCapabilities = backend.capabilities

    private val mutableSessions = MutableStateFlow<Set<PeripheralSession>>(emptySet())
    override val sessions: StateFlow<Set<PeripheralSession>> = mutableSessions.asStateFlow()

    private val requestChannel = Channel<GattServerRequest>(requestCapacity)
    override val requests: Flow<GattServerRequest> = requestChannel.receiveAsFlow()

    private val eventChannel = Channel<PeripheralEvent>(eventCapacity)
    override val events: Flow<PeripheralEvent> = eventChannel.receiveAsFlow()

    private val readinessChannel = Channel<NotificationReadiness>(eventCapacity)
    override val notificationReadiness: Flow<NotificationReadiness> =
        readinessChannel.receiveAsFlow()

    private val backendEventChannel = Channel<BackendEvent>(Channel.UNLIMITED)
    private val backendEventProcessor = managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (event in backendEventChannel) {
            try {
                processBackendEvent(event)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                eventChannel.trySend(PeripheralEvent.PlatformFailure(cause))
            }
        }
    }

    override suspend fun start(config: PeripheralConfig) {
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (current != PeripheralManagerState.Stopped) {
                throw PeripheralLifecycleException(
                    "Peripheral can only start from Stopped; current state is $current",
                )
            }

            val generation = ++nextGeneration
            activeGeneration = generation
            activeConfig = config
            mutableState.value = PeripheralManagerState.Starting
            try {
                backend.start(config, BackendEventSink(generation))
                mutableState.value = PeripheralManagerState.Running
            } catch (cause: Throwable) {
                withContext(NonCancellable) {
                    try {
                        backend.stop()
                    } catch (rollbackFailure: Throwable) {
                        cause.addSuppressed(rollbackFailure)
                    }
                    activeGeneration = NoGeneration
                    activeConfig = null
                    closeAllSessions()
                    mutableState.value = PeripheralManagerState.Failed(cause)
                }
                throw cause
            }
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            when (mutableState.value) {
                PeripheralManagerState.Stopped,
                PeripheralManagerState.Closed,
                -> return

                else -> withContext(NonCancellable) {
                    mutableState.value = PeripheralManagerState.Stopping
                    activeGeneration = NoGeneration
                    activeConfig = null
                    closeAllSessions()
                    try {
                        backend.stop()
                        mutableState.value = PeripheralManagerState.Stopped
                    } catch (cause: Throwable) {
                        mutableState.value = PeripheralManagerState.Failed(cause)
                        throw cause
                    }
                }
            }
        }
    }

    override suspend fun close() {
        lifecycleMutex.withLock {
            if (mutableState.value == PeripheralManagerState.Closed) return

            withContext(NonCancellable) {
                val shouldStop = mutableState.value != PeripheralManagerState.Stopped
                var failure: Throwable? = null
                activeGeneration = NoGeneration
                activeConfig = null

                if (shouldStop) {
                    mutableState.value = PeripheralManagerState.Stopping
                    closeAllSessions()
                    try {
                        backend.stop()
                    } catch (cause: Throwable) {
                        failure = cause
                    }
                }

                try {
                    backend.close()
                } catch (cause: Throwable) {
                    if (failure == null) {
                        failure = cause
                    } else {
                        failure.addSuppressed(cause)
                    }
                }

                backendEventChannel.close(failure)
                requestChannel.close(failure)
                eventChannel.close(failure)
                readinessChannel.close(failure)
                mutableState.value = PeripheralManagerState.Closed
                managerJob.cancel()

                failure?.let { throw it }
            }
        }
    }

    private suspend fun processBackendEvent(event: BackendEvent) {
        lifecycleMutex.withLock {
            if (event.generation != activeGeneration) return

            when (event) {
                is BackendEvent.SessionOpened -> {
                    if (mutableState.value != PeripheralManagerState.Running) return
                    openSession(event.sessionId, event.maximumUpdateValueLength)
                }

                is BackendEvent.SessionClosed -> closeSession(event.sessionId, event.cause)
                is BackendEvent.SubscriptionsChanged -> sessionRegistry[event.sessionId]
                    ?.updateSubscriptions(event.subscriptions)

                is BackendEvent.MaximumUpdateValueLengthChanged ->
                    sessionRegistry[event.sessionId]
                        ?.updateMaximumUpdateValueLength(event.maximumUpdateValueLength)

                is BackendEvent.NotificationReady -> {
                    readinessChannel.trySend(event.readiness)
                    if (event.readiness is NotificationReadiness.Session) {
                        sessionRegistry[event.readiness.sessionId]?.signalNotificationReady()
                    }
                }

                is BackendEvent.Request -> processRequest(event.request)
                is BackendEvent.PlatformFailure ->
                    eventChannel.trySend(PeripheralEvent.PlatformFailure(event.cause))
            }
        }
    }

    private suspend fun openSession(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ) = sessionMutex.withLock {
        val existing = sessionRegistry[sessionId]
        if (existing != null) {
            existing.updateMaximumUpdateValueLength(maximumUpdateValueLength)
            return@withLock
        }

        sessionRegistry[sessionId] = DefaultPeripheralSession(
            id = sessionId,
            backend = backend,
            parentJob = managerJob,
            maximumUpdateValueLength = maximumUpdateValueLength,
        )
        publishSessions()
    }

    private suspend fun closeSession(
        sessionId: PeripheralSessionId,
        cause: Throwable?,
    ) {
        expirePendingResponses(sessionId)
        val session = sessionMutex.withLock {
            sessionRegistry.remove(sessionId).also { publishSessions() }
        } ?: return

        session.close()
        eventChannel.trySend(PeripheralEvent.SessionClosed(sessionId, cause))
    }

    private suspend fun closeAllSessions() {
        expireAllPendingResponses()
        val sessions = sessionMutex.withLock {
            sessionRegistry.values.toList().also {
                sessionRegistry.clear()
                publishSessions()
            }
        }
        sessions.forEach { it.close() }
    }

    private fun publishSessions() {
        mutableSessions.value = sessionRegistry.values.toSet()
    }

    private suspend fun processRequest(request: BackendGattServerRequest) {
        val session = sessionRegistry[request.sessionId]
        val responseDeadline = activeConfig?.responseDeadline
        if (session == null || responseDeadline == null) {
            rejectRequest(request)
            return
        }

        val responseHandle = request.responder?.let { responder ->
            DefaultGattResponseHandle { status, value -> responder.respond(status, value) }
        }
        if (responseHandle != null) {
            registerPendingResponse(request.sessionId, responseHandle)
        }

        val publicRequest = request.toPublicRequest(session, responseHandle)
        if (requestChannel.trySend(publicRequest).isFailure) {
            if (responseHandle != null) {
                try {
                    responseHandle.expire(GattResponseStatus.UnlikelyError)
                } finally {
                    unregisterPendingResponse(request.sessionId, responseHandle)
                }
            }
            emitRequestDropped(request)
            return
        }

        if (responseHandle != null) {
            scheduleResponseDeadline(
                sessionId = request.sessionId,
                requestType = request.requestType,
                responseHandle = responseHandle,
                responseDeadline = responseDeadline,
            )
        }
    }

    private suspend fun registerPendingResponse(
        sessionId: PeripheralSessionId,
        responseHandle: DefaultGattResponseHandle,
    ) {
        pendingResponseMutex.withLock {
            pendingResponses.getOrPut(sessionId, ::mutableSetOf).add(responseHandle)
        }
    }

    private suspend fun unregisterPendingResponse(
        sessionId: PeripheralSessionId,
        responseHandle: DefaultGattResponseHandle,
    ) {
        pendingResponseMutex.withLock {
            val sessionResponses = pendingResponses[sessionId] ?: return@withLock
            sessionResponses.remove(responseHandle)
            if (sessionResponses.isEmpty()) pendingResponses.remove(sessionId)
        }
    }

    private fun scheduleResponseDeadline(
        sessionId: PeripheralSessionId,
        requestType: dev.bluefalcon.peripheral.GattRequestType,
        responseHandle: DefaultGattResponseHandle,
        responseDeadline: Duration,
    ) {
        managerScope.launch {
            val completed = withTimeoutOrNull(responseDeadline) {
                responseHandle.awaitTerminal()
                true
            } == true

            if (!completed) {
                try {
                    if (responseHandle.expire(GattResponseStatus.UnlikelyError)) {
                        eventChannel.trySend(
                            PeripheralEvent.ResponseTimedOut(sessionId, requestType),
                        )
                    }
                } catch (cause: Throwable) {
                    eventChannel.trySend(PeripheralEvent.PlatformFailure(cause))
                    eventChannel.trySend(
                        PeripheralEvent.ResponseTimedOut(sessionId, requestType),
                    )
                }
            }

            unregisterPendingResponse(sessionId, responseHandle)
        }
    }

    private suspend fun expirePendingResponses(sessionId: PeripheralSessionId) {
        val responses = pendingResponseMutex.withLock {
            pendingResponses.remove(sessionId)?.toList().orEmpty()
        }
        responses.forEach { it.expire() }
    }

    private suspend fun expireAllPendingResponses() {
        val responses = pendingResponseMutex.withLock {
            pendingResponses.values.flatten().also { pendingResponses.clear() }
        }
        responses.forEach { it.expire() }
    }

    private fun rejectRequest(request: BackendGattServerRequest) {
        request.responder?.respond(GattResponseStatus.UnlikelyError, null)
        emitRequestDropped(request)
    }

    private fun emitRequestDropped(request: BackendGattServerRequest) {
        eventChannel.trySend(
            PeripheralEvent.RequestDropped(
                sessionId = request.sessionId,
                requestType = request.requestType,
            ),
        )
    }

    private fun BackendGattServerRequest.toPublicRequest(
        session: DefaultPeripheralSession,
        responseHandle: DefaultGattResponseHandle?,
    ): GattServerRequest = when (this) {
        is BackendCharacteristicReadRequest -> GattCharacteristicReadRequest(
            session = session,
            serviceId = serviceId,
            characteristicId = characteristicId,
            offset = offset,
            response = requireNotNull(responseHandle),
        )

        is BackendCharacteristicWriteRequest -> GattCharacteristicWriteRequest(
            session = session,
            serviceId = serviceId,
            characteristicId = characteristicId,
            offset = offset,
            value = value,
            preparedWrite = preparedWrite,
            response = responseHandle,
        )

        is BackendDescriptorReadRequest -> GattDescriptorReadRequest(
            session = session,
            serviceId = serviceId,
            characteristicId = characteristicId,
            descriptorId = descriptorId,
            offset = offset,
            response = requireNotNull(responseHandle),
        )

        is BackendDescriptorWriteRequest -> GattDescriptorWriteRequest(
            session = session,
            serviceId = serviceId,
            characteristicId = characteristicId,
            descriptorId = descriptorId,
            offset = offset,
            value = value,
            preparedWrite = preparedWrite,
            response = responseHandle,
        )

        is BackendExecuteWriteRequest -> GattExecuteWriteRequest(
            session = session,
            execute = execute,
            response = requireNotNull(responseHandle),
        )
    }

    private inner class BackendEventSink(
        private val generation: Long,
    ) : PeripheralBackendEventSink {
        override fun onSessionOpened(
            sessionId: PeripheralSessionId,
            maximumUpdateValueLength: Int?,
        ) {
            submit(BackendEvent.SessionOpened(generation, sessionId, maximumUpdateValueLength))
        }

        override fun onSessionClosed(sessionId: PeripheralSessionId, cause: Throwable?) {
            submit(BackendEvent.SessionClosed(generation, sessionId, cause))
        }

        override fun onSubscriptionsChanged(
            sessionId: PeripheralSessionId,
            subscriptions: Set<GattCharacteristicId>,
        ) {
            submit(
                BackendEvent.SubscriptionsChanged(
                    generation,
                    sessionId,
                    subscriptions.toSet(),
                ),
            )
        }

        override fun onMaximumUpdateValueLengthChanged(
            sessionId: PeripheralSessionId,
            maximumUpdateValueLength: Int?,
        ) {
            submit(
                BackendEvent.MaximumUpdateValueLengthChanged(
                    generation,
                    sessionId,
                    maximumUpdateValueLength,
                ),
            )
        }

        override fun onNotificationReady(readiness: NotificationReadiness) {
            submit(BackendEvent.NotificationReady(generation, readiness))
        }

        override fun onRequest(request: BackendGattServerRequest) {
            submit(BackendEvent.Request(generation, request))
        }

        override fun onPlatformFailure(cause: Throwable) {
            submit(BackendEvent.PlatformFailure(generation, cause))
        }

        private fun submit(event: BackendEvent) {
            backendEventChannel.trySend(event)
        }
    }

    private sealed interface BackendEvent {
        val generation: Long

        data class SessionOpened(
            override val generation: Long,
            val sessionId: PeripheralSessionId,
            val maximumUpdateValueLength: Int?,
        ) : BackendEvent

        data class SessionClosed(
            override val generation: Long,
            val sessionId: PeripheralSessionId,
            val cause: Throwable?,
        ) : BackendEvent

        data class SubscriptionsChanged(
            override val generation: Long,
            val sessionId: PeripheralSessionId,
            val subscriptions: Set<GattCharacteristicId>,
        ) : BackendEvent

        data class MaximumUpdateValueLengthChanged(
            override val generation: Long,
            val sessionId: PeripheralSessionId,
            val maximumUpdateValueLength: Int?,
        ) : BackendEvent

        data class NotificationReady(
            override val generation: Long,
            val readiness: NotificationReadiness,
        ) : BackendEvent

        data class Request(
            override val generation: Long,
            val request: BackendGattServerRequest,
        ) : BackendEvent

        data class PlatformFailure(
            override val generation: Long,
            val cause: Throwable,
        ) : BackendEvent
    }

    private companion object {
        const val DefaultBufferCapacity = 64
        const val NoGeneration = -1L
    }
}

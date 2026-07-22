package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicReadRequest
import dev.bluefalcon.peripheral.GattCharacteristicWrite
import dev.bluefalcon.peripheral.GattCharacteristicWriteBatchRequest
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
import dev.bluefalcon.peripheral.copyForBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private val inactivityJobs = mutableMapOf<PeripheralSessionId, Job>()
    private val inactivityTokens = mutableMapOf<PeripheralSessionId, Long>()
    private var nextGeneration = 0L
    private var nextInactivityToken = 0L
    private var activeGeneration = NoGeneration
    private var activeConfig: PeripheralConfig? = null
    private var closeStarted = false
    private val closeCompletion = CompletableDeferred<Throwable?>()

    private val mutableState = MutableStateFlow<PeripheralManagerState>(
        PeripheralManagerState.Stopped,
    )
    override val state: StateFlow<PeripheralManagerState> = mutableState.asStateFlow()

    override val capabilities: PeripheralCapabilities = backend.capabilities

    private val mutableSessions = MutableStateFlow<Set<PeripheralSession>>(emptySet())
    override val sessions: StateFlow<Set<PeripheralSession>> = mutableSessions.asStateFlow()

    private val requestChannel = Channel<GattServerRequest>(requestCapacity)
    override val requests: Flow<GattServerRequest> = requestChannel.receiveAsFlow()

    private val requestIngressChannel = Channel<RegisteredBackendRequest>(requestCapacity)
    private val requestIngressProcessor = managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (registeredRequest in requestIngressChannel) {
            try {
                processRegisteredRequest(registeredRequest)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                registeredRequest.deadlineJob?.cancel()
                registeredRequest.responseHandle?.expire(GattResponseStatus.UnlikelyError)
                eventChannel.trySend(PeripheralEvent.PlatformFailure(cause))
            }
        }
    }

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
        val backendConfig = config.copyForBackend()
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (closeStarted || current != PeripheralManagerState.Stopped) {
                throw PeripheralLifecycleException(
                    "Peripheral can only start from Stopped; current state is $current",
                )
            }

            val generation = ++nextGeneration
            activeGeneration = generation
            activeConfig = backendConfig
            mutableState.value = PeripheralManagerState.Starting
            try {
                backend.start(
                    backendConfig,
                    BackendEventSink(generation, backendConfig.responseDeadline),
                )
                mutableState.value = PeripheralManagerState.Running
            } catch (cause: Throwable) {
                withContext(NonCancellable) {
                    val closingSessions = beginCloseAllSessions()
                    try {
                        backend.stop()
                    } catch (rollbackFailure: Throwable) {
                        cause.addSuppressed(rollbackFailure)
                    }
                    finishCloseAllSessions(closingSessions)
                    activeGeneration = NoGeneration
                    activeConfig = null
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
                    val closingSessions = beginCloseAllSessions()
                    var failure: Throwable? = null
                    try {
                        backend.stop()
                    } catch (cause: Throwable) {
                        failure = cause
                    }
                    finishCloseAllSessions(closingSessions)
                    if (failure == null) {
                        mutableState.value = PeripheralManagerState.Stopped
                    } else {
                        mutableState.value = PeripheralManagerState.Failed(failure)
                        throw failure
                    }
                }
            }
        }
    }

    override suspend fun close() = withContext(NonCancellable) {
        var failure: Throwable? = null
        val ownsClose = lifecycleMutex.withLock {
            if (closeStarted) {
                return@withLock false
            }
            closeStarted = true

            val shouldStop = mutableState.value != PeripheralManagerState.Stopped
            activeGeneration = NoGeneration
            activeConfig = null
            val closingSessions = beginCloseAllSessions()

            if (shouldStop) {
                mutableState.value = PeripheralManagerState.Stopping
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
            finishCloseAllSessions(closingSessions)

            backendEventChannel.close(failure)
            requestIngressChannel.close(failure)
            requestChannel.close(failure)
            eventChannel.close(failure)
            readinessChannel.close(failure)
            true
        }

        if (!ownsClose) {
            closeCompletion.await()?.let { throw it }
            return@withContext
        }

        requestIngressProcessor.join()
        backendEventProcessor.join()
        managerJob.cancelAndJoin()
        lifecycleMutex.withLock {
            mutableState.value = PeripheralManagerState.Closed
        }
        closeCompletion.complete(failure)
        failure?.let { throw it }
        Unit
    }

    private suspend fun processBackendEvent(event: BackendEvent) {
        lifecycleMutex.withLock {
            if (event.generation != activeGeneration) {
                return
            }

            when (event) {
                is BackendEvent.SessionOpened -> {
                    if (mutableState.value != PeripheralManagerState.Running) return
                    openSession(event.sessionId, event.maximumUpdateValueLength)
                }

                is BackendEvent.SessionClosed -> closeSession(event.sessionId, event.cause)
                is BackendEvent.SubscriptionsChanged -> {
                    val session = openSession(event.sessionId, null)
                    session.updateSubscriptions(event.subscriptions)
                    refreshInactivityDeadline(event.sessionId)
                }

                is BackendEvent.MaximumUpdateValueLengthChanged -> {
                    val session = openSession(event.sessionId, null)
                    session.updateMaximumUpdateValueLength(event.maximumUpdateValueLength)
                    refreshInactivityDeadline(event.sessionId)
                }

                is BackendEvent.NotificationReady -> {
                    readinessChannel.trySend(event.readiness)
                    when (event.readiness) {
                        NotificationReadiness.Manager -> sessionRegistry.values.forEach {
                            it.signalNotificationReady()
                        }

                        is NotificationReadiness.Session ->
                            sessionRegistry[event.readiness.sessionId]?.signalNotificationReady()
                    }
                }

                is BackendEvent.PlatformFailure ->
                    eventChannel.trySend(PeripheralEvent.PlatformFailure(event.cause))

                is BackendEvent.InactivityExpired -> {
                    if (inactivityTokens[event.sessionId] != event.token) return
                    inactivityJobs.remove(event.sessionId)
                    inactivityTokens.remove(event.sessionId)
                    if (isSessionInactive(event.sessionId)) {
                        closeSession(event.sessionId, null)
                    } else {
                        refreshInactivityDeadline(event.sessionId)
                    }
                }
            }
        }
    }

    private suspend fun openSession(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ): DefaultPeripheralSession = sessionMutex.withLock {
        val existing = sessionRegistry[sessionId]
        if (existing != null) {
            if (maximumUpdateValueLength != null) {
                existing.updateMaximumUpdateValueLength(maximumUpdateValueLength)
            }
            refreshInactivityDeadline(sessionId)
            return@withLock existing
        }

        val session = DefaultPeripheralSession(
            id = sessionId,
            backend = backend,
            parentJob = managerJob,
            maximumUpdateValueLength = maximumUpdateValueLength,
        )
        sessionRegistry[sessionId] = session
        publishSessions()
        refreshInactivityDeadline(sessionId)
        session
    }

    private suspend fun closeSession(
        sessionId: PeripheralSessionId,
        cause: Throwable?,
    ) {
        cancelInactivityDeadline(sessionId)
        expirePendingResponses(sessionId)
        val session = sessionMutex.withLock {
            sessionRegistry.remove(sessionId).also { publishSessions() }
        } ?: return

        session.beginClose()
        session.finishClose()
        eventChannel.trySend(PeripheralEvent.SessionClosed(sessionId, cause))
    }

    private suspend fun beginCloseAllSessions(): List<DefaultPeripheralSession> {
        inactivityJobs.values.forEach { it.cancel() }
        inactivityJobs.clear()
        inactivityTokens.clear()
        expireAllPendingResponses()
        val sessions = sessionMutex.withLock {
            sessionRegistry.values.toList().also {
                sessionRegistry.clear()
                publishSessions()
            }
        }
        sessions.forEach { it.beginClose() }
        return sessions
    }

    private suspend fun finishCloseAllSessions(sessions: List<DefaultPeripheralSession>) {
        sessions.forEach { it.finishClose() }
    }

    private fun publishSessions() {
        mutableSessions.value = sessionRegistry.values.toSet()
    }

    private suspend fun processRegisteredRequest(
        registeredRequest: RegisteredBackendRequest,
    ) = lifecycleMutex.withLock {
        val request = registeredRequest.request
        if (registeredRequest.generation != activeGeneration) {
            rejectRegisteredRequest(registeredRequest)
            return@withLock
        }

        val responseHandle = registeredRequest.responseHandle
        if (responseHandle != null && !responseHandle.isPending()) {
            return@withLock
        }

        val session = sessionRegistry[request.sessionId] ?: openSession(request.sessionId, null)
        if (responseHandle != null) {
            registerPendingResponse(request.sessionId, responseHandle)
            cancelInactivityDeadline(request.sessionId)
        }

        val publicRequest = request.toPublicRequest(session, responseHandle)
        if (requestChannel.trySend(publicRequest).isFailure) {
            if (responseHandle != null) {
                registeredRequest.deadlineJob?.cancel()
                try {
                    responseHandle.expire(GattResponseStatus.UnlikelyError)
                } finally {
                    unregisterPendingResponse(request.sessionId, responseHandle)
                }
            }
            emitRequestDropped(request)
            refreshInactivityDeadline(request.sessionId)
            return@withLock
        }

        if (responseHandle == null) {
            refreshInactivityDeadline(request.sessionId)
        }
    }

    private suspend fun rejectRegisteredRequest(
        registeredRequest: RegisteredBackendRequest,
    ) {
        registeredRequest.deadlineJob?.cancel()
        registeredRequest.responseHandle?.expire(GattResponseStatus.UnlikelyError)
        emitRequestDropped(registeredRequest.request)
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
    ): Job = managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        lifecycleMutex.withLock {
            refreshInactivityDeadline(sessionId)
        }
    }

    private suspend fun refreshInactivityDeadline(sessionId: PeripheralSessionId) {
        cancelInactivityDeadline(sessionId)
        if (capabilities.connectionLifecycleVisibility) return
        if (!isSessionInactive(sessionId)) return

        val timeout = activeConfig?.inactiveSessionTimeout ?: return
        val generation = activeGeneration
        if (generation == NoGeneration) return
        val token = ++nextInactivityToken
        inactivityTokens[sessionId] = token
        inactivityJobs[sessionId] = managerScope.launch {
            delay(timeout)
            backendEventChannel.trySend(
                BackendEvent.InactivityExpired(
                    generation = generation,
                    sessionId = sessionId,
                    token = token,
                ),
            )
        }
    }

    private suspend fun isSessionInactive(sessionId: PeripheralSessionId): Boolean {
        val session = sessionRegistry[sessionId] ?: return false
        if (session.hasActiveSubscriptions) return false
        return pendingResponseMutex.withLock {
            pendingResponses[sessionId].isNullOrEmpty()
        }
    }

    private fun cancelInactivityDeadline(sessionId: PeripheralSessionId) {
        inactivityJobs.remove(sessionId)?.cancel()
        inactivityTokens.remove(sessionId)
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

        is BackendCharacteristicWriteBatchRequest -> GattCharacteristicWriteBatchRequest(
            session = session,
            writes = writes.map { write ->
                GattCharacteristicWrite(
                    serviceId = write.serviceId,
                    characteristicId = write.characteristicId,
                    offset = write.offset,
                    value = write.value,
                )
            },
            response = requireNotNull(responseHandle),
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
        private val responseDeadline: Duration,
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
            val responseHandle = request.responder?.let { responder ->
                DefaultGattResponseHandle { status, value -> responder.respond(status, value) }
            }
            val deadlineJob = responseHandle?.let {
                scheduleResponseDeadline(
                    sessionId = request.sessionId,
                    requestType = request.requestType,
                    responseHandle = it,
                    responseDeadline = responseDeadline,
                )
            }
            val registeredRequest = RegisteredBackendRequest(
                generation = generation,
                request = request,
                responseHandle = responseHandle,
                deadlineJob = deadlineJob,
            )

            if (requestIngressChannel.trySend(registeredRequest).isFailure) {
                deadlineJob?.cancel()
                try {
                    responseHandle?.tryExpire(GattResponseStatus.UnlikelyError)
                } catch (_: Throwable) {
                    // The manager is already closed, so there is no live event stream to report to.
                }
                emitRequestDropped(request)
            }
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

        data class PlatformFailure(
            override val generation: Long,
            val cause: Throwable,
        ) : BackendEvent

        data class InactivityExpired(
            override val generation: Long,
            val sessionId: PeripheralSessionId,
            val token: Long,
        ) : BackendEvent
    }

    private companion object {
        const val DefaultBufferCapacity = 64
        const val NoGeneration = -1L
    }

    private data class RegisteredBackendRequest(
        val generation: Long,
        val request: BackendGattServerRequest,
        val responseHandle: DefaultGattResponseHandle?,
        val deadlineJob: Job?,
    )
}

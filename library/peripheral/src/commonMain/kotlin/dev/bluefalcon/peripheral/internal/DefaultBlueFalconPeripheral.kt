package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralEvent
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralManagerState
import dev.bluefalcon.peripheral.PeripheralSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(
        coroutineContext.minusKey(Job) + managerJob,
    )

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

    private val backendEventSink = object : PeripheralBackendEventSink {
        override fun onSessionOpened(
            sessionId: dev.bluefalcon.peripheral.PeripheralSessionId,
            maximumUpdateValueLength: Int?,
        ) = Unit

        override fun onSessionClosed(
            sessionId: dev.bluefalcon.peripheral.PeripheralSessionId,
            cause: Throwable?,
        ) = Unit

        override fun onSubscriptionsChanged(
            sessionId: dev.bluefalcon.peripheral.PeripheralSessionId,
            subscriptions: Set<dev.bluefalcon.peripheral.GattCharacteristicId>,
        ) = Unit

        override fun onMaximumUpdateValueLengthChanged(
            sessionId: dev.bluefalcon.peripheral.PeripheralSessionId,
            maximumUpdateValueLength: Int?,
        ) = Unit

        override fun onNotificationReady(readiness: NotificationReadiness) {
            readinessChannel.trySend(readiness)
        }

        override fun onRequest(request: BackendGattServerRequest) {
            request.responder?.respond(GattResponseStatus.UnlikelyError, null)
            eventChannel.trySend(
                PeripheralEvent.RequestDropped(
                    sessionId = request.sessionId,
                    requestType = request.requestType,
                ),
            )
        }

        override fun onPlatformFailure(cause: Throwable) {
            eventChannel.trySend(PeripheralEvent.PlatformFailure(cause))
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

            mutableState.value = PeripheralManagerState.Starting
            try {
                backend.start(config, backendEventSink)
                mutableState.value = PeripheralManagerState.Running
            } catch (cause: Throwable) {
                withContext(NonCancellable) {
                    try {
                        backend.stop()
                    } catch (rollbackFailure: Throwable) {
                        cause.addSuppressed(rollbackFailure)
                    }
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

                requestChannel.close(failure)
                eventChannel.close(failure)
                readinessChannel.close(failure)
                mutableState.value = PeripheralManagerState.Closed
                managerJob.cancel()

                failure?.let { throw it }
            }
        }
    }

    private companion object {
        const val DefaultBufferCapacity = 64
    }
}

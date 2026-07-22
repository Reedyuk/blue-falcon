package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultPeripheralSession(
    override val id: PeripheralSessionId,
    private val backend: PeripheralBackend,
    parentJob: Job,
    maximumUpdateValueLength: Int?,
) : PeripheralSession {

    private val stateMutex = Mutex()
    private val backendOperationMutex = Mutex()
    private val sessionJob = SupervisorJob(parentJob)

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Active)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableSubscriptions = MutableStateFlow<Set<GattCharacteristicId>>(emptySet())
    override val subscriptions: StateFlow<Set<GattCharacteristicId>> =
        mutableSubscriptions.asStateFlow()

    private val mutableMaximumUpdateValueLength = MutableStateFlow(maximumUpdateValueLength)
    override val maximumUpdateValueLength: StateFlow<Int?> =
        mutableMaximumUpdateValueLength.asStateFlow()

    private val readinessChannel = Channel<Unit>(Channel.CONFLATED)
    override val notificationReady: Flow<Unit> = readinessChannel.receiveAsFlow()

    internal val hasActiveSubscriptions: Boolean
        get() = mutableSubscriptions.value.isNotEmpty()

    override suspend fun notify(
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult {
        val copiedValue = value.copyOf()
        val callerContext = currentCoroutineContext()
        val operation = stateMutex.withLock {
            if (mutableState.value != SessionState.Active) {
                return@withLock null
            }

            CoroutineScope(callerContext.minusKey(Job) + sessionJob).async(
                start = CoroutineStart.LAZY,
            ) {
                backendOperationMutex.withLock {
                    backend.notify(
                        sessionId = id,
                        characteristic = characteristic,
                        value = copiedValue,
                        mode = mode,
                    )
                }
            }
        } ?: return NotificationResult.Disconnected
        operation.start()
        return try {
            operation.await()
        } catch (cause: CancellationException) {
            if (mutableState.value != SessionState.Active) {
                NotificationResult.Disconnected
            } else {
                throw cause
            }
        } finally {
            operation.cancel()
        }
    }

    override suspend fun disconnect(): DisconnectResult {
        val callerContext = currentCoroutineContext()
        val operation = stateMutex.withLock {
            if (mutableState.value != SessionState.Active) {
                return@withLock null
            }

            CoroutineScope(callerContext.minusKey(Job) + sessionJob).async(
                start = CoroutineStart.LAZY,
            ) {
                backendOperationMutex.withLock {
                    backend.disconnect(id)
                }
            }
        }
            ?: return DisconnectResult.AlreadyDisconnected
        operation.start()
        return try {
            operation.await()
        } catch (cause: CancellationException) {
            if (mutableState.value != SessionState.Active) {
                DisconnectResult.AlreadyDisconnected
            } else {
                throw cause
            }
        } finally {
            operation.cancel()
        }
    }

    internal suspend fun updateSubscriptions(
        subscriptions: Set<GattCharacteristicId>,
    ) = stateMutex.withLock {
        if (mutableState.value == SessionState.Active) {
            mutableSubscriptions.value = subscriptions.toSet()
        }
    }

    internal suspend fun updateMaximumUpdateValueLength(value: Int?) =
        stateMutex.withLock {
            if (mutableState.value == SessionState.Active) {
                mutableMaximumUpdateValueLength.value = value
            }
        }

    internal fun signalNotificationReady() {
        if (mutableState.value == SessionState.Active) {
            readinessChannel.trySend(Unit)
        }
    }

    internal suspend fun beginClose() = stateMutex.withLock {
        if (mutableState.value != SessionState.Active) return@withLock
        mutableState.value = SessionState.Closing
        readinessChannel.close()
        sessionJob.cancel()
    }

    internal suspend fun finishClose() {
        sessionJob.join()
        stateMutex.withLock {
            mutableState.value = SessionState.Closed
        }
    }

    internal suspend fun close() {
        beginClose()
        finishClose()
    }
}

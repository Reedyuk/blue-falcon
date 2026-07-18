package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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

    private val operationMutex = Mutex()
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

    override suspend fun notify(
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult = operationMutex.withLock {
        if (mutableState.value != SessionState.Active) {
            return@withLock NotificationResult.Disconnected
        }

        backend.notify(
            sessionId = id,
            characteristic = characteristic,
            value = value.copyOf(),
            mode = mode,
        )
    }

    override suspend fun disconnect(): DisconnectResult = operationMutex.withLock {
        if (mutableState.value != SessionState.Active) {
            return@withLock DisconnectResult.AlreadyDisconnected
        }

        backend.disconnect(id)
    }

    internal suspend fun updateSubscriptions(
        subscriptions: Set<GattCharacteristicId>,
    ) = operationMutex.withLock {
        if (mutableState.value == SessionState.Active) {
            mutableSubscriptions.value = subscriptions.toSet()
        }
    }

    internal suspend fun updateMaximumUpdateValueLength(value: Int?) =
        operationMutex.withLock {
            if (mutableState.value == SessionState.Active) {
                mutableMaximumUpdateValueLength.value = value
            }
        }

    internal suspend fun signalNotificationReady() = operationMutex.withLock {
        if (mutableState.value == SessionState.Active) {
            readinessChannel.trySend(Unit)
        }
    }

    internal suspend fun close() = operationMutex.withLock {
        if (mutableState.value == SessionState.Closed) return@withLock

        mutableState.value = SessionState.Closing
        readinessChannel.close()
        mutableState.value = SessionState.Closed
        sessionJob.cancel()
    }
}

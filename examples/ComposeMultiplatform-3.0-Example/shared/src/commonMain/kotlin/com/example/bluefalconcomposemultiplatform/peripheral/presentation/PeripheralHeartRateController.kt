package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.ClientCharacteristicConfigurationDescriptor
import com.example.bluefalconcomposemultiplatform.peripheral.HeartRateGatt
import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicReadRequest
import dev.bluefalcon.peripheral.GattCharacteristicWrite
import dev.bluefalcon.peripheral.GattCharacteristicWriteBatchRequest
import dev.bluefalcon.peripheral.GattCharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattDescriptorReadRequest
import dev.bluefalcon.peripheral.GattDescriptorWriteRequest
import dev.bluefalcon.peripheral.GattExecuteWriteRequest
import dev.bluefalcon.peripheral.GattResponseHandle
import dev.bluefalcon.peripheral.GattResponseResult
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralManagerState
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.plugins.queue.QueueSendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Hosts the standard Bluetooth SIG Heart Rate Monitor GATT profile ([HeartRateGatt]).
 *
 * Reading the Body Sensor Location characteristic requires bonding: the first read
 * attempt from a session is rejected with [GattResponseStatus.InsufficientAuthentication],
 * which the platform Bluetooth stack turns into a bonding/pairing request. Once bonding
 * completes, the central retries the read and the controller returns the sensor location.
 *
 * The Heart Rate Measurement characteristic is normally notify-only, but when
 * [PeripheralServerState.bondOnHeartRateRead] is enabled, an explicit read of it is gated
 * by bonding the same way: the first read is rejected with
 * [GattResponseStatus.InsufficientAuthentication] to request bonding immediately, and once
 * bonded, reads succeed with the current heart rate reading. This lets bonding be
 * requested as soon as the heart rate characteristic is read, instead of waiting for a
 * Body Sensor Location read.
 */
class PeripheralHeartRateController(
    private val runtime: PeripheralExampleRuntime?,
    private val scope: CoroutineScope,
    initialBondingRequired: Boolean = false,
    initialBondOnHeartRateRead: Boolean = false,
) {
    private val supported = runtime?.manager?.capabilities?.let { capabilities ->
        capabilities.localGattServer && capabilities.connectableAdvertising
    } == true
    private val mutableState = MutableStateFlow(
        PeripheralServerState(
            supported = supported,
            profile = PeripheralProfile.HEART_RATE_MONITOR,
            bondingRequired = initialBondingRequired,
            bondOnHeartRateRead = initialBondOnHeartRateRead,
        ),
    )
    val state: StateFlow<PeripheralServerState> = mutableState.asStateFlow()

    private val config = heartRateConfig()
    private var subscriptionObserverJob: Job? = null
    private var simulationJob: Job? = null

    private val bondingRequestedSessions = mutableSetOf<PeripheralSessionId>()
    private val bondedSessions = mutableSetOf<PeripheralSessionId>()

    init {
        if (supported) {
            val currentRuntime = checkNotNull(runtime)
            scope.launch {
                currentRuntime.manager.state.collect { managerState ->
                    mutableState.update { current ->
                        current.copy(managerState = managerState)
                    }
                    if (managerState != PeripheralManagerState.Running) {
                        stopSimulationInternal()
                    }
                }
            }
            scope.launch {
                currentRuntime.manager.sessions.collect { sessions ->
                    subscriptionObserverJob?.cancelAndJoin()

                    val activeSessionIds = sessions.map { session -> session.id }.toSet()
                    bondingRequestedSessions.retainAll(activeSessionIds)
                    bondedSessions.retainAll(activeSessionIds)

                    val subscribedSessionCount = sessions.count { session ->
                        HeartRateGatt.heartRateMeasurementId in session.subscriptions.value
                    }
                    mutableState.update { current ->
                        current.copy(
                            sessionCount = sessions.size,
                            subscribedSessionCount = subscribedSessionCount,
                            bondedSessionCount = bondedSessions.size,
                        )
                    }

                    subscriptionObserverJob = if (sessions.isEmpty()) {
                        null
                    } else {
                        launch {
                            combine(sessions.map { session -> session.subscriptions }) {
                                subscriptions ->
                                subscriptions.count { subscription ->
                                    HeartRateGatt.heartRateMeasurementId in subscription
                                }
                            }.collect { count ->
                                mutableState.update { current ->
                                    current.copy(subscribedSessionCount = count)
                                }
                            }
                        }
                    }
                }
            }
            scope.launch {
                currentRuntime.manager.requests.collect { request ->
                    val terminal = TerminalHeartRateResponse(request.response)
                    var decision: HeartRateRequestDecision? = null
                    try {
                        decision = decideRequest(request)
                        val result = terminal.respond(
                            status = decision.status,
                            value = decision.value,
                        )
                        commitBondedSession(decision, result)
                        appendLog(decision.log.withResponseResult(result))
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        if (terminal.attempted) {
                            appendLog(
                                "${decision?.log ?: "Request"}; terminal response " +
                                    "failed: ${cause.message ?: "unknown error"}",
                            )
                        } else {
                            handleProcessingFailure(terminal, cause)
                        }
                    }
                }
            }
        }
    }

    suspend fun start() {
        val manager = runtime?.manager?.takeIf { supported } ?: return
        try {
            manager.start(config)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            appendLog("Start failed: ${cause.message ?: "unknown error"}")
        }
    }

    suspend fun stop() {
        val manager = runtime?.manager?.takeIf { supported } ?: return
        stopSimulationInternal()
        try {
            manager.stop()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            appendLog("Stop failed: ${cause.message ?: "unknown error"}")
        }
    }

    fun toggleHeartRateSimulation() {
        if (!state.value.canToggleHeartRateSimulation) return
        if (simulationJob != null) {
            scope.launch { stopSimulationInternal() }
        } else {
            startSimulation()
        }
    }

    fun setBondingRequired(required: Boolean) {
        if (!state.value.canToggleBondingRequirement) return
        mutableState.update { current -> current.copy(bondingRequired = required) }
    }

    fun setBondOnHeartRateRead(required: Boolean) {
        if (!state.value.canToggleBondOnHeartRateRead) return
        mutableState.update { current -> current.copy(bondOnHeartRateRead = required) }
    }

    private fun startSimulation() {
        val currentRuntime = runtime?.takeIf { supported } ?: return
        if (simulationJob != null) return
        mutableState.update { current -> current.copy(simulatingHeartRate = true) }
        simulationJob = scope.launch {
            try {
                while (isActive) {
                    delay(1.seconds)
                    val bpm = nextSimulatedBpm()
                    mutableState.update { current -> current.copy(heartRateBpm = bpm) }
                    sendHeartRateMeasurement(currentRuntime, bpm)
                }
            } finally {
                mutableState.update { current -> current.copy(simulatingHeartRate = false) }
            }
        }
    }

    private suspend fun stopSimulationInternal() {
        val job = simulationJob ?: return
        simulationJob = null
        job.cancelAndJoin()
        mutableState.update { current -> current.copy(simulatingHeartRate = false) }
    }

    private fun nextSimulatedBpm(): Int {
        val current = mutableState.value.heartRateBpm
        val delta = Random.nextInt(-3, 4)
        return (current + delta).coerceIn(MIN_SIMULATED_BPM, MAX_SIMULATED_BPM)
    }

    private suspend fun sendHeartRateMeasurement(
        currentRuntime: PeripheralExampleRuntime,
        bpm: Int,
    ) {
        if (currentRuntime.manager.state.value != PeripheralManagerState.Running) return

        // Flags byte 0x00: heart rate value format is UINT8, no optional fields present.
        val payload = heartRateMeasurementPayload(bpm)
        val targets = currentRuntime.manager.sessions.value.filter { session ->
            HeartRateGatt.heartRateMeasurementId in session.subscriptions.value
        }
        coroutineScope {
            targets.forEach { session ->
                launch {
                    val result = try {
                        currentRuntime.queue.send(
                            session = session,
                            characteristic = HeartRateGatt.heartRateMeasurementId,
                            value = payload.copyOf(),
                        )
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        QueueSendResult.Failed(cause)
                    }
                    appendLog(
                        "${session.id.value}: heart rate $bpm bpm -> " +
                            result.toLogLabel(),
                    )
                }
            }
        }
    }

    private fun decideRequest(request: GattServerRequest): HeartRateRequestDecision {
        val logPrefix = "Session ${request.sessionId.value}"
        return when (request) {
            is GattCharacteristicReadRequest -> decideRead(request, logPrefix)
            is GattCharacteristicWriteRequest -> decideWrite(request, logPrefix)
            is GattCharacteristicWriteBatchRequest -> {
                if (request.writes.any { write -> !write.hasHeartRateHandle() }) {
                    invalidHandleDecision(logPrefix)
                } else {
                    unsupportedDecision(logPrefix, "write batch")
                }
            }

            is GattDescriptorReadRequest -> {
                if (!request.hasHeartRateHandle()) {
                    invalidHandleDecision(logPrefix)
                } else {
                    unsupportedDecision(logPrefix, "descriptor read")
                }
            }

            is GattDescriptorWriteRequest -> decideDescriptorWrite(request, logPrefix)

            is GattExecuteWriteRequest -> unsupportedDecision(logPrefix, "execute write")
        }
    }

    private fun decideRead(
        request: GattCharacteristicReadRequest,
        logPrefix: String,
    ): HeartRateRequestDecision {
        if (request.serviceId != HeartRateGatt.serviceId) {
            return invalidHandleDecision(logPrefix)
        }
        return when (request.characteristicId) {
            HeartRateGatt.bodySensorLocationId ->
                decideBodySensorLocationRead(request, logPrefix)

            HeartRateGatt.heartRateMeasurementId ->
                decideHeartRateMeasurementRead(request, logPrefix)

            else -> invalidHandleDecision(logPrefix)
        }
    }

    private fun decideBodySensorLocationRead(
        request: GattCharacteristicReadRequest,
        logPrefix: String,
    ): HeartRateRequestDecision {
        if (!mutableState.value.bondingRequired) {
            return HeartRateRequestDecision(
                status = GattResponseStatus.Success,
                value = byteArrayOf(HeartRateGatt.BODY_SENSOR_LOCATION_CHEST),
                log = "$logPrefix read body sensor location (bonding not enforced)",
            )
        }
        return decideBondingGatedRead(
            sessionId = request.sessionId,
            logPrefix = logPrefix,
            characteristicLabel = "body sensor location",
            value = { byteArrayOf(HeartRateGatt.BODY_SENSOR_LOCATION_CHEST) },
        )
    }

    private fun decideHeartRateMeasurementRead(
        request: GattCharacteristicReadRequest,
        logPrefix: String,
    ): HeartRateRequestDecision {
        if (!mutableState.value.bondOnHeartRateRead) {
            return HeartRateRequestDecision(
                status = GattResponseStatus.ReadNotPermitted,
                log = "$logPrefix rejected: heart rate measurement is notify-only",
            )
        }
        return decideBondingGatedRead(
            sessionId = request.sessionId,
            logPrefix = logPrefix,
            characteristicLabel = "heart rate measurement",
            value = { currentHeartRateMeasurementPayload() },
        )
    }

    /**
     * Shared bonding gate for a characteristic read: rejects the first read from a session
     * with [GattResponseStatus.InsufficientAuthentication] to request bonding immediately,
     * then succeeds with [value] once the platform Bluetooth stack retries the read after
     * bonding completes.
     */
    private fun decideBondingGatedRead(
        sessionId: PeripheralSessionId,
        logPrefix: String,
        characteristicLabel: String,
        value: () -> ByteArray,
    ): HeartRateRequestDecision = decideBondingGatedOperation(
        sessionId = sessionId,
        logPrefix = logPrefix,
        operationLabel = "read $characteristicLabel",
        value = value,
    )

    /**
     * Shared bonding gate for a GATT server operation (a characteristic read, or a CCCD
     * write to enable notifications): rejects the operation with
     * [GattResponseStatus.InsufficientAuthentication] the first time a session attempts
     * it, which the platform Bluetooth stack turns into a bonding/pairing request. Once
     * bonding completes, the central automatically retries the operation and it succeeds
     * - this is the standard Bluetooth behaviour for an unbonded link touching a
     * security-gated attribute, rather than dropping the connection or silently
     * withholding data.
     */
    private fun decideBondingGatedOperation(
        sessionId: PeripheralSessionId,
        logPrefix: String,
        operationLabel: String,
        value: (() -> ByteArray)? = null,
    ): HeartRateRequestDecision {
        if (sessionId in bondedSessions) {
            return HeartRateRequestDecision(
                status = GattResponseStatus.Success,
                value = value?.invoke(),
                log = "$logPrefix $operationLabel (already bonded)",
            )
        }
        if (sessionId in bondingRequestedSessions) {
            // The central retried the operation, which only happens after the platform
            // Bluetooth stack has successfully established an encrypted/bonded link.
            return HeartRateRequestDecision(
                status = GattResponseStatus.Success,
                value = value?.invoke(),
                stagedBondedSessionId = sessionId,
                log = "$logPrefix bonded; accepted $operationLabel",
            )
        }
        bondingRequestedSessions += sessionId
        return HeartRateRequestDecision(
            status = GattResponseStatus.InsufficientAuthentication,
            log = "$logPrefix $operationLabel: requesting bonding " +
                "(insufficient authentication)",
        )
    }

    private fun currentHeartRateMeasurementPayload(): ByteArray =
        heartRateMeasurementPayload(mutableState.value.heartRateBpm)

    private fun decideWrite(
        request: GattCharacteristicWriteRequest,
        logPrefix: String,
    ): HeartRateRequestDecision {
        if (!request.hasHeartRateHandle() ||
            request.characteristicId != HeartRateGatt.heartRateControlPointId
        ) {
            return invalidHandleDecision(logPrefix)
        }
        if (request.preparedWrite) {
            return unsupportedDecision(logPrefix, "prepared write")
        }
        val value = request.value
        return when {
            value.size != 1 -> HeartRateRequestDecision(
                status = GattResponseStatus.InvalidAttributeValueLength,
                log = "$logPrefix control point write rejected: expected 1 byte, " +
                    "got ${value.size}",
            )

            value[0] == HeartRateGatt.CONTROL_POINT_RESET_ENERGY_EXPENDED ->
                HeartRateRequestDecision(
                    status = GattResponseStatus.Success,
                    log = "$logPrefix reset energy expended",
                )

            else -> HeartRateRequestDecision(
                status = GattResponseStatus.RequestNotSupported,
                log = "$logPrefix control point write rejected: unsupported " +
                    "opcode ${value[0]}",
            )
        }
    }

    private fun commitBondedSession(
        decision: HeartRateRequestDecision,
        result: GattResponseResult?,
    ) {
        val stagedSessionId = decision.stagedBondedSessionId ?: return
        if (result == null || result == GattResponseResult.Responded) {
            bondedSessions += stagedSessionId
            bondingRequestedSessions -= stagedSessionId
            mutableState.update { current ->
                current.copy(bondedSessionCount = bondedSessions.size)
            }
        }
    }

    private fun invalidHandleDecision(logPrefix: String) = HeartRateRequestDecision(
        status = GattResponseStatus.InvalidHandle,
        log = "$logPrefix rejected unknown GATT handle",
    )

    private fun unsupportedDecision(
        logPrefix: String,
        operation: String,
    ) = HeartRateRequestDecision(
        status = GattResponseStatus.RequestNotSupported,
        log = "$logPrefix unsupported request: $operation",
    )

    /**
     * Writes to the Client Characteristic Configuration Descriptor (`0x2902`) are how a
     * central enables/disables Heart Rate Measurement notifications. On platforms that
     * forward this write to the app (e.g. Android), rejecting it - as
     * [unsupportedDecision] previously did unconditionally for every descriptor write -
     * both surfaces an ATT error to the central and prevents the peripheral framework
     * from ever registering the subscription, so [sendHeartRateMeasurement] never finds
     * a subscribed session to notify. Accept it here so subscriptions work correctly;
     * any other descriptor remains unsupported.
     *
     * When [PeripheralServerState.bondingRequired] is enabled, this CCCD write is gated
     * by bonding the same way a protected characteristic read is: standard Bluetooth
     * behaviour for a security-gated attribute on an unbonded link is for the GATT server
     * to reject the operation with [GattResponseStatus.InsufficientAuthentication] (not to
     * drop the connection or silently withhold notifications), which prompts the platform
     * Bluetooth stack to bond and then retry the write automatically.
     */
    private fun decideDescriptorWrite(
        request: GattDescriptorWriteRequest,
        logPrefix: String,
    ): HeartRateRequestDecision {
        if (!request.hasHeartRateHandle()) {
            return invalidHandleDecision(logPrefix)
        }
        if (request.descriptorId != ClientCharacteristicConfigurationDescriptor.id) {
            return unsupportedDecision(logPrefix, "descriptor write")
        }
        if (!mutableState.value.bondingRequired) {
            return HeartRateRequestDecision(
                status = GattResponseStatus.Success,
                log = "$logPrefix accepted client characteristic configuration write " +
                    "(bonding not enforced)",
            )
        }
        return decideBondingGatedOperation(
            sessionId = request.sessionId,
            logPrefix = logPrefix,
            operationLabel = "client characteristic configuration write",
        )
    }

    private suspend fun handleProcessingFailure(
        terminal: TerminalHeartRateResponse,
        requestFailure: Exception,
    ) {
        val requestMessage = "Request failed: ${requestFailure.message ?: "unknown error"}"
        try {
            val result = terminal.respond(GattResponseStatus.UnlikelyError, null)
            appendLog(requestMessage.withResponseResult(result))
        } catch (cause: CancellationException) {
            appendLog(requestMessage)
            throw cause
        } catch (cause: Error) {
            appendLog(requestMessage)
            throw cause
        } catch (cause: Exception) {
            appendLog(
                "$requestMessage; fallback response failed: " +
                    (cause.message ?: "unknown error"),
            )
        }
    }

    private fun String.withResponseResult(result: GattResponseResult?): String =
        when (result) {
            null,
            GattResponseResult.Responded,
            -> this

            GattResponseResult.AlreadyResponded -> "$this; response was already completed"
            GattResponseResult.Expired -> "$this; response expired"
        }

    private fun GattCharacteristicWrite.hasHeartRateHandle(): Boolean =
        serviceId == HeartRateGatt.serviceId

    private fun GattCharacteristicWriteRequest.hasHeartRateHandle(): Boolean =
        serviceId == HeartRateGatt.serviceId

    private fun GattDescriptorReadRequest.hasHeartRateHandle(): Boolean =
        serviceId == HeartRateGatt.serviceId

    private fun GattDescriptorWriteRequest.hasHeartRateHandle(): Boolean =
        serviceId == HeartRateGatt.serviceId

    private fun appendLog(message: String) {
        mutableState.update { current ->
            current.copy(log = (current.log + message).takeLast(MAX_LOG_ENTRIES))
        }
    }
}

private fun QueueSendResult.toLogLabel(): String = when (this) {
    QueueSendResult.Sent -> "Sent"
    QueueSendResult.QueueFull -> "QueueFull"
    QueueSendResult.PayloadTooLarge -> "PayloadTooLarge"
    QueueSendResult.Disconnected -> "Disconnected"
    QueueSendResult.Unsupported -> "Unsupported"
    is QueueSendResult.Failed ->
        "Failed: ${cause.message ?: cause::class.simpleName ?: "unknown error"}"
}

private class HeartRateRequestDecision(
    val status: GattResponseStatus,
    value: ByteArray? = null,
    val stagedBondedSessionId: PeripheralSessionId? = null,
    val log: String,
) {
    private val responseValue = value?.copyOf()

    val value: ByteArray?
        get() = responseValue?.copyOf()
}

private class TerminalHeartRateResponse(
    private val handle: GattResponseHandle?,
) {
    var attempted: Boolean = false
        private set

    suspend fun respond(
        status: GattResponseStatus,
        value: ByteArray?,
    ): GattResponseResult? {
        val response = handle ?: return null
        check(!attempted) { "A GATT response was already attempted" }
        attempted = true
        return response.respond(status, value?.copyOf())
    }
}

private fun heartRateConfig() = PeripheralConfig(
    advertiseConfig = AdvertiseConfig(
        localName = "Blue Falcon Heart Rate",
        serviceUuids = listOf(HeartRateGatt.serviceUuid),
        services = listOf(
            GattServiceConfig(
                uuid = HeartRateGatt.serviceUuid,
                characteristics = listOf(
                    GattCharacteristicConfig(
                        uuid = HeartRateGatt.heartRateMeasurementUuid,
                        properties = setOf(CharacteristicProperty.NOTIFY),
                    ),
                    GattCharacteristicConfig(
                        uuid = HeartRateGatt.bodySensorLocationUuid,
                        properties = setOf(CharacteristicProperty.READ),
                        initialValue = byteArrayOf(
                            HeartRateGatt.BODY_SENSOR_LOCATION_CHEST,
                        ),
                    ),
                    GattCharacteristicConfig(
                        uuid = HeartRateGatt.heartRateControlPointUuid,
                        properties = setOf(CharacteristicProperty.WRITE),
                    ),
                ),
            ),
        ),
    ),
    restorationIdentifier = HeartRateGatt.restorationIdentifier,
)

private fun heartRateMeasurementPayload(bpm: Int): ByteArray {
    // Flags byte 0x00: heart rate value format is UINT8, no optional fields present.
    return byteArrayOf(0x00, bpm.toByte())
}

private const val MIN_SIMULATED_BPM = 55
private const val MAX_SIMULATED_BPM = 120
private const val MAX_LOG_ENTRIES = 100

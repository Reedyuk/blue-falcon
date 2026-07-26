package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.EchoGatt
import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicReadRequest
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
import dev.bluefalcon.plugins.queue.QueueSendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PeripheralEchoController(
    private val runtime: PeripheralExampleRuntime?,
    scope: CoroutineScope,
) {
    private val supported = runtime?.manager?.capabilities?.let { capabilities ->
        capabilities.localGattServer && capabilities.connectableAdvertising
    } == true
    private val mutableState = MutableStateFlow(
        PeripheralServerState(supported = supported),
    )
    val state: StateFlow<PeripheralServerState> = mutableState.asStateFlow()

    private val config = echoConfig()
    private var subscriptionObserverJob: Job? = null
    private var echoValue = DEFAULT_ECHO_VALUE.copyOf()

    init {
        if (supported) {
            val currentRuntime = checkNotNull(runtime)
            scope.launch {
                currentRuntime.manager.state.collect { managerState ->
                    mutableState.update { current ->
                        current.copy(managerState = managerState)
                    }
                }
            }
            scope.launch {
                currentRuntime.manager.sessions.collect { sessions ->
                    subscriptionObserverJob?.cancelAndJoin()

                    val subscribedSessionCount = sessions.count { session ->
                        EchoGatt.characteristicId in session.subscriptions.value
                    }
                    mutableState.update { current ->
                        current.copy(
                            sessionCount = sessions.size,
                            subscribedSessionCount = subscribedSessionCount,
                        )
                    }

                    subscriptionObserverJob = if (sessions.isEmpty()) {
                        null
                    } else {
                        launch {
                            combine(sessions.map { session -> session.subscriptions }) {
                                subscriptions ->
                                subscriptions.count { subscription ->
                                    EchoGatt.characteristicId in subscription
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
                    val terminal = TerminalResponse(request.response)
                    var decision: RequestDecision? = null
                    try {
                        decision = decideRequest(request)
                        val result = terminal.respond(
                            status = decision.status,
                            value = decision.value,
                        )
                        val committed = commitStagedEchoValue(decision, result)
                        appendLog(
                            decision.logAfterCommit(committed)
                                .withResponseResult(result),
                        )
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
        try {
            manager.stop()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            appendLog("Stop failed: ${cause.message ?: "unknown error"}")
        }
    }

    fun setPayloadText(value: String) {
        mutableState.update { current ->
            current.copy(payloadText = value)
        }
    }

    suspend fun sendNotification() {
        val currentRuntime = runtime?.takeIf { supported } ?: return
        if (currentRuntime.manager.state.value != PeripheralManagerState.Running) return

        val payload = state.value.payloadText.encodeToByteArray()
        if (payload.isEmpty()) return

        val targets = currentRuntime.manager.sessions.value.filter { session ->
            EchoGatt.characteristicId in session.subscriptions.value
        }
        coroutineScope {
            targets.map { session ->
                async {
                    val result = try {
                        currentRuntime.queue.send(
                            session = session,
                            characteristic = EchoGatt.characteristicId,
                            value = payload.copyOf(),
                        )
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        QueueSendResult.Failed(cause)
                    }
                    appendLog("${session.id.value}: ${result.toLogLabel()}")
                }
            }.awaitAll()
        }
    }

    private fun decideRequest(request: GattServerRequest): RequestDecision {
        val session = request.sessionId
        val logPrefix = "Session $session"
        return when (request) {
            is GattCharacteristicReadRequest -> decideRead(request, logPrefix)
            is GattCharacteristicWriteRequest -> decideWrite(request, logPrefix)
            is GattCharacteristicWriteBatchRequest -> {
                if (request.writes.any { write ->
                        write.serviceId != EchoGatt.serviceId ||
                            write.characteristicId != EchoGatt.characteristicId
                    }
                ) {
                    invalidHandleDecision(logPrefix)
                } else {
                    unsupportedDecision(logPrefix, "write batch")
                }
            }

            is GattDescriptorReadRequest -> {
                if (!request.hasEchoHandle()) {
                    invalidHandleDecision(logPrefix)
                } else {
                    unsupportedDecision(logPrefix, "descriptor read")
                }
            }

            is GattDescriptorWriteRequest -> {
                if (!request.hasEchoHandle()) {
                    invalidHandleDecision(logPrefix)
                } else {
                    unsupportedDecision(logPrefix, "descriptor write")
                }
            }

            is GattExecuteWriteRequest ->
                unsupportedDecision(logPrefix, "execute write")
        }
    }

    private fun decideRead(
        request: GattCharacteristicReadRequest,
        logPrefix: String,
    ): RequestDecision {
        if (!request.hasEchoHandle()) {
            return invalidHandleDecision(logPrefix)
        }
        if (request.offset !in 0..echoValue.size) {
            return RequestDecision(
                status = GattResponseStatus.InvalidOffset,
                log = "$logPrefix read rejected: invalid offset ${request.offset}",
            )
        }

        val value = echoValue.copyOfRange(request.offset, echoValue.size)
        return RequestDecision(
            status = GattResponseStatus.Success,
            value = value.copyOf(),
            log = "$logPrefix read ${value.size} byte(s) at offset ${request.offset}",
        )
    }

    private fun decideWrite(
        request: GattCharacteristicWriteRequest,
        logPrefix: String,
    ): RequestDecision {
        if (!request.hasEchoHandle()) {
            return invalidHandleDecision(logPrefix)
        }
        if (request.preparedWrite) {
            return unsupportedDecision(logPrefix, "prepared write")
        }
        if (request.offset !in 0..echoValue.size) {
            return RequestDecision(
                status = GattResponseStatus.InvalidOffset,
                log = "$logPrefix write rejected: invalid offset ${request.offset}",
            )
        }

        val written = request.value.copyOf()
        if (written.size > MAX_ECHO_VALUE_SIZE - request.offset) {
            return RequestDecision(
                status = GattResponseStatus.InvalidAttributeValueLength,
                log = "$logPrefix write rejected: ${written.size} byte(s) at " +
                    "offset ${request.offset} exceed $MAX_ECHO_VALUE_SIZE byte(s)",
            )
        }
        val requiredSize = maxOf(echoValue.size, request.offset + written.size)
        val updated = ByteArray(requiredSize)
        echoValue.copyInto(updated)
        written.copyInto(updated, destinationOffset = request.offset)

        return RequestDecision(
            status = GattResponseStatus.Success,
            stagedEchoValue = updated,
            committedLog =
                "$logPrefix wrote ${written.size} byte(s) at offset ${request.offset}",
            log =
                "$logPrefix staged ${written.size} byte(s) at offset ${request.offset}",
        )
    }

    private fun commitStagedEchoValue(
        decision: RequestDecision,
        result: GattResponseResult?,
    ): Boolean {
        val stagedValue = decision.stagedEchoValue ?: return false
        if (result == null || result == GattResponseResult.Responded) {
            echoValue = stagedValue
            return true
        }
        return false
    }

    private fun invalidHandleDecision(logPrefix: String) = RequestDecision(
        status = GattResponseStatus.InvalidHandle,
        log = "$logPrefix rejected unknown GATT handle",
    )

    private fun unsupportedDecision(
        logPrefix: String,
        operation: String,
    ) = RequestDecision(
        status = GattResponseStatus.RequestNotSupported,
        log = "$logPrefix unsupported request: $operation",
    )

    private suspend fun handleProcessingFailure(
        terminal: TerminalResponse,
        requestFailure: Exception,
    ) {
        val requestMessage =
            "Request failed: ${requestFailure.message ?: "unknown error"}"

        try {
            val result = terminal.respond(GattResponseStatus.UnlikelyError, null)
            appendLog(requestMessage.withFallbackResponseResult(result))
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
            return
        }
    }

    private fun String.withResponseResult(result: GattResponseResult?): String =
        when (result) {
            null,
            GattResponseResult.Responded,
            -> this

            GattResponseResult.AlreadyResponded ->
                "$this; response was already completed"

            GattResponseResult.Expired ->
                "$this; response expired"
        }

    private fun String.withFallbackResponseResult(
        result: GattResponseResult?,
    ): String = when (result) {
        null,
        GattResponseResult.Responded,
        -> this

        GattResponseResult.AlreadyResponded ->
            "$this; fallback response was already completed"

        GattResponseResult.Expired ->
            "$this; fallback response expired"
    }

    private fun GattCharacteristicReadRequest.hasEchoHandle(): Boolean =
        serviceId == EchoGatt.serviceId &&
            characteristicId == EchoGatt.characteristicId

    private fun GattCharacteristicWriteRequest.hasEchoHandle(): Boolean =
        serviceId == EchoGatt.serviceId &&
            characteristicId == EchoGatt.characteristicId

    private fun GattDescriptorReadRequest.hasEchoHandle(): Boolean =
        serviceId == EchoGatt.serviceId &&
            characteristicId == EchoGatt.characteristicId

    private fun GattDescriptorWriteRequest.hasEchoHandle(): Boolean =
        serviceId == EchoGatt.serviceId &&
            characteristicId == EchoGatt.characteristicId

    private fun appendLog(message: String) {
        mutableState.update { current ->
            current.copy(
                log = (current.log + message).takeLast(MAX_LOG_ENTRIES),
            )
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

private class RequestDecision(
    val status: GattResponseStatus,
    value: ByteArray? = null,
    stagedEchoValue: ByteArray? = null,
    private val committedLog: String? = null,
    val log: String,
) {
    private val responseValue = value?.copyOf()
    private val copiedStagedEchoValue = stagedEchoValue?.copyOf()

    val value: ByteArray?
        get() = responseValue?.copyOf()
    val stagedEchoValue: ByteArray?
        get() = copiedStagedEchoValue?.copyOf()

    fun logAfterCommit(committed: Boolean): String =
        if (committed) committedLog ?: log else log
}

private class TerminalResponse(
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

private fun echoConfig() = PeripheralConfig(
    advertiseConfig = AdvertiseConfig(
        localName = "Blue Falcon Echo",
        serviceUuids = listOf(EchoGatt.serviceUuid),
        services = listOf(
            GattServiceConfig(
                uuid = EchoGatt.serviceUuid,
                characteristics = listOf(
                    GattCharacteristicConfig(
                        uuid = EchoGatt.characteristicUuid,
                        properties = setOf(
                            CharacteristicProperty.READ,
                            CharacteristicProperty.WRITE,
                            CharacteristicProperty.WRITE_NO_RESPONSE,
                            CharacteristicProperty.NOTIFY,
                            CharacteristicProperty.INDICATE,
                        ),
                        initialValue = DEFAULT_ECHO_VALUE,
                    ),
                ),
            ),
        ),
    ),
    restorationIdentifier = EchoGatt.restorationIdentifier,
)

private val DEFAULT_ECHO_VALUE = "Hello from Blue Falcon".encodeToByteArray()
private const val MAX_ECHO_VALUE_SIZE = 512
private const val MAX_LOG_ENTRIES = 100

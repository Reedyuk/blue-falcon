package dev.bluefalcon.example.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
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
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.plugins.queue.QueuePlugin
import dev.bluefalcon.plugins.queue.QueueSendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

class PeripheralEchoServer(
    private val peripheral: BlueFalconPeripheral,
    scope: CoroutineScope,
) {
    private val queue = peripheral.plugins.install(QueuePlugin) {
        maxPendingItemsPerSession = 64
        maxPendingBytes = 64 * 1024
    }
    private var value = DEFAULT_ECHO_VALUE.copyOf()
    private val requestJob = scope.launch {
        peripheral.requests.collect(::handleRequest)
    }

    suspend fun start() {
        peripheral.start(echoPeripheralConfig())
    }

    suspend fun stop() {
        peripheral.stop()
    }

    suspend fun notifySubscribers(
        value: ByteArray,
    ): Map<PeripheralSessionId, QueueSendResult> {
        val sessions = peripheral.sessions.value.filter { session ->
            EchoGatt.characteristicId in session.subscriptions.value
        }

        return coroutineScope {
            sessions.map { session ->
                async {
                    val result = try {
                        queue.send(
                            session = session,
                            characteristic = EchoGatt.characteristicId,
                            value = value.copyOf(),
                        )
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        QueueSendResult.Failed(cause)
                    }
                    session.id to result
                }
            }.awaitAll().toMap()
        }
    }

    suspend fun close() {
        requestJob.cancelAndJoin()
        peripheral.close()
    }

    private suspend fun handleRequest(request: GattServerRequest) {
        val terminal = TerminalResponse(request.response)
        try {
            val decision = decideRequest(request)
            terminal.respond(decision.status, decision.value)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Error) {
            throw cause
        } catch (cause: Exception) {
            if (!terminal.attempted) {
                respondWithFallback(terminal)
            }
        }
    }

    private suspend fun respondWithFallback(terminal: TerminalResponse) {
        try {
            terminal.respond(GattResponseStatus.UnlikelyError, null)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Error) {
            throw cause
        } catch (_: Exception) {
            // The handle is already terminal, or its platform deadline will finish the request.
        }
    }

    private fun decideRequest(request: GattServerRequest): RequestDecision =
        when (request) {
            is GattCharacteristicReadRequest -> decideRead(request)
            is GattCharacteristicWriteRequest -> decideWrite(request)
            is GattCharacteristicWriteBatchRequest -> {
                if (request.writes.any { write ->
                        write.serviceId != EchoGatt.serviceId ||
                            write.characteristicId != EchoGatt.characteristicId
                    }
                ) {
                    RequestDecision(GattResponseStatus.InvalidHandle)
                } else {
                    RequestDecision(GattResponseStatus.RequestNotSupported)
                }
            }

            is GattDescriptorReadRequest -> {
                if (request.hasEchoHandle()) {
                    RequestDecision(GattResponseStatus.RequestNotSupported)
                } else {
                    RequestDecision(GattResponseStatus.InvalidHandle)
                }
            }

            is GattDescriptorWriteRequest -> {
                if (request.hasEchoHandle()) {
                    RequestDecision(GattResponseStatus.RequestNotSupported)
                } else {
                    RequestDecision(GattResponseStatus.InvalidHandle)
                }
            }

            is GattExecuteWriteRequest ->
                RequestDecision(GattResponseStatus.RequestNotSupported)
        }

    private fun decideRead(
        request: GattCharacteristicReadRequest,
    ): RequestDecision {
        if (!request.hasEchoHandle()) {
            return RequestDecision(GattResponseStatus.InvalidHandle)
        }
        if (request.offset !in 0..value.size) {
            return RequestDecision(GattResponseStatus.InvalidOffset)
        }

        return RequestDecision(
            status = GattResponseStatus.Success,
            value = value.copyOfRange(request.offset, value.size),
        )
    }

    private fun decideWrite(
        request: GattCharacteristicWriteRequest,
    ): RequestDecision {
        if (!request.hasEchoHandle()) {
            return RequestDecision(GattResponseStatus.InvalidHandle)
        }
        if (request.preparedWrite) {
            return RequestDecision(GattResponseStatus.RequestNotSupported)
        }
        if (request.offset !in 0..value.size) {
            return RequestDecision(GattResponseStatus.InvalidOffset)
        }

        val written = request.value.copyOf()
        val updated = ByteArray(maxOf(value.size, request.offset + written.size))
        value.copyInto(updated)
        written.copyInto(updated, destinationOffset = request.offset)
        value = updated.copyOf()
        return RequestDecision(GattResponseStatus.Success)
    }
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

@OptIn(ExperimentalUuidApi::class)
object EchoGatt {
    const val serviceUuid = "84f7e120-63fd-4f79-8b08-5b9780a36a94"
    const val characteristicUuid = "84f7e121-63fd-4f79-8b08-5b9780a36a94"
    const val restorationIdentifier = "dev.bluefalcon.example.echo-peripheral"

    val serviceId: GattServiceId = GattServiceId(serviceUuid.toUuid())
    val characteristicId: GattCharacteristicId =
        GattCharacteristicId(characteristicUuid.toUuid())
}

private fun echoPeripheralConfig() = PeripheralConfig(
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
                        initialValue = DEFAULT_ECHO_VALUE.copyOf(),
                    ),
                ),
            ),
        ),
    ),
    restorationIdentifier = EchoGatt.restorationIdentifier,
)

private class RequestDecision(
    val status: GattResponseStatus,
    value: ByteArray? = null,
) {
    private val copiedValue = value?.copyOf()

    val value: ByteArray?
        get() = copiedValue?.copyOf()
}

private class TerminalResponse(
    private val handle: GattResponseHandle?,
) {
    var attempted = false
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

private val DEFAULT_ECHO_VALUE = "Hello from Blue Falcon".encodeToByteArray()

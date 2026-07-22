package dev.bluefalcon.peripheral.android

import android.content.Context
import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.AdvertiserState
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.CharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NoOpBluetoothAdvertiser
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteBatchRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorReadRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorWriteRequest
import dev.bluefalcon.peripheral.internal.BackendExecuteWriteRequest
import dev.bluefalcon.peripheral.internal.BackendGattServerRequest
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Deprecated(
    message = "Use createBlueFalconPeripheral for production peripheral-role BLE",
    replaceWith = ReplaceWith("createBlueFalconPeripheral(context, logger)"),
)
class AndroidBluetoothAdvertiser : BluetoothAdvertiser {
    private val logger: Logger?
    private val backend: AndroidPeripheralBackend
    private val lock = Any()
    private val lifecycleMutex = Mutex()
    private val eventSink = LegacyBackendEventSink()

    private val _state = MutableStateFlow(AdvertiserState.Idle)
    override val state: StateFlow<AdvertiserState> = _state.asStateFlow()

    private val _writeRequests =
        MutableSharedFlow<CharacteristicWriteRequest>(extraBufferCapacity = 64)
    override val characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest> =
        _writeRequests

    private val characteristicValues = mutableMapOf<CharacteristicKey, ByteArray>()
    private val descriptorValues = mutableMapOf<DescriptorKey, ByteArray>()
    private val notificationModes = mutableMapOf<GattCharacteristicId, List<NotificationMode>>()
    private val activeSessions = linkedSetOf<PeripheralSessionId>()
    private val subscriptions = mutableMapOf<PeripheralSessionId, Set<GattCharacteristicId>>()

    constructor(
        context: Context,
        logger: Logger? = null,
    ) : this(FrameworkAndroidBluetoothStack(context, logger), logger)

    internal constructor(
        stack: AndroidBluetoothStack,
        logger: Logger? = null,
    ) {
        this.logger = logger
        backend = AndroidPeripheralBackend(
            stack = stack,
            logger = logger,
            allowAdvertisingWithoutGattServer = true,
        )
    }

    override suspend fun startAdvertising(config: AdvertiseConfig) {
        lifecycleMutex.withLock {
            if (_state.value != AdvertiserState.Idle) {
                stopAdvertisingLocked()
            }
            seedAttributeState(config)

            try {
                backend.start(PeripheralConfig(config), eventSink)
                _state.value = AdvertiserState.Advertising
                logger?.info("AndroidAdvertiser: advertising started")
            } catch (cause: Throwable) {
                clearLegacyState()
                _state.value = AdvertiserState.Error
                logger?.error("AndroidAdvertiser: advertising failed", cause)
                throw cause
            }
        }
    }

    override suspend fun stopAdvertising() = lifecycleMutex.withLock {
        stopAdvertisingLocked()
    }

    private suspend fun stopAdvertisingLocked() {
        if (_state.value == AdvertiserState.Idle) return

        try {
            backend.stop()
        } finally {
            clearLegacyState()
            _state.value = AdvertiserState.Idle
            logger?.info("AndroidAdvertiser: stopped")
        }
    }

    override suspend fun updateCharacteristicValue(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
    ) {
        val serviceId = serviceUuid.toServiceIdOrNull()
        val characteristicId = characteristicUuid.toCharacteristicIdOrNull()
        if (serviceId == null || characteristicId == null) {
            logger?.error(
                "AndroidAdvertiser: invalid characteristic $characteristicUuid or service $serviceUuid",
            )
            return
        }

        val key = CharacteristicKey(serviceId, characteristicId)
        val update = synchronized(lock) {
            if (key !in characteristicValues) {
                null
            } else {
                val copiedValue = value.copyOf()
                characteristicValues[key] = copiedValue
                NotificationUpdate(
                    sessions = activeSessions.filter { sessionId ->
                        characteristicId in subscriptions[sessionId].orEmpty()
                    },
                    modes = notificationModes[characteristicId]
                        ?: listOf(NotificationMode.Notification),
                    value = copiedValue,
                )
            }
        }

        if (update == null) {
            logger?.error(
                "AndroidAdvertiser: characteristic $characteristicUuid not found in service $serviceUuid",
            )
            return
        }

        update.sessions.forEach { sessionId ->
            for (mode in update.modes) {
                val result = backend.notify(
                    sessionId = sessionId,
                    characteristic = characteristicId,
                    value = update.value,
                    mode = mode,
                )
                if (result != NotificationResult.Unsupported) break
            }
        }
    }

    private fun seedAttributeState(config: AdvertiseConfig) {
        synchronized(lock) {
            characteristicValues.clear()
            descriptorValues.clear()
            notificationModes.clear()
            activeSessions.clear()
            subscriptions.clear()

            config.services.forEach { service ->
                val serviceId = GattServiceId(Uuid.parse(service.uuid))
                service.characteristics.forEach { characteristic ->
                    val characteristicId = GattCharacteristicId(Uuid.parse(characteristic.uuid))
                    characteristicValues[CharacteristicKey(serviceId, characteristicId)] =
                        characteristic.initialValue?.copyOf() ?: ByteArray(0)
                    notificationModes(characteristic.properties).takeIf { it.isNotEmpty() }
                        ?.let { modes ->
                            notificationModes[characteristicId] = modes
                        }
                    characteristic.descriptors.forEach { descriptor ->
                        val descriptorId = GattDescriptorId(Uuid.parse(descriptor.uuid))
                        descriptorValues[
                            DescriptorKey(serviceId, characteristicId, descriptorId)
                        ] = descriptor.initialValue?.copyOf() ?: ByteArray(0)
                    }
                }
            }
        }
    }

    private fun clearLegacyState() {
        synchronized(lock) {
            characteristicValues.clear()
            descriptorValues.clear()
            notificationModes.clear()
            activeSessions.clear()
            subscriptions.clear()
        }
    }

    private fun handleRequest(request: BackendGattServerRequest) {
        when (request) {
            is BackendCharacteristicReadRequest -> {
                val value = synchronized(lock) {
                    (characteristicValues[
                        CharacteristicKey(request.serviceId, request.characteristicId)
                    ] ?: ByteArray(0)).sliceFrom(request.offset)
                }
                request.responder.respond(GattResponseStatus.Success, value)
            }

            is BackendCharacteristicWriteRequest -> {
                val value = request.value
                synchronized(lock) {
                    val key = CharacteristicKey(request.serviceId, request.characteristicId)
                    characteristicValues[key] = (characteristicValues[key] ?: ByteArray(0))
                        .writtenAt(request.offset, value)
                }
                request.responder?.respond(GattResponseStatus.Success, value)
                _writeRequests.tryEmit(
                    CharacteristicWriteRequest(
                        serviceUuid = request.serviceId.uuid.toString(),
                        characteristicUuid = request.characteristicId.uuid.toString(),
                        value = value.copyOf(),
                        requestId = request.requestId,
                    ),
                )
            }

            is BackendCharacteristicWriteBatchRequest ->
                request.responder.respond(GattResponseStatus.RequestNotSupported, null)

            is BackendDescriptorReadRequest -> {
                val value = synchronized(lock) {
                    (descriptorValues[
                        DescriptorKey(
                            request.serviceId,
                            request.characteristicId,
                            request.descriptorId,
                        )
                    ] ?: ByteArray(0)).sliceFrom(request.offset)
                }
                request.responder.respond(GattResponseStatus.Success, value)
            }

            is BackendDescriptorWriteRequest -> {
                val value = request.value
                synchronized(lock) {
                    val key = DescriptorKey(
                        request.serviceId,
                        request.characteristicId,
                        request.descriptorId,
                    )
                    descriptorValues[key] = (descriptorValues[key] ?: ByteArray(0))
                        .writtenAt(request.offset, value)
                }
                request.responder?.respond(GattResponseStatus.Success, value)
            }

            is BackendExecuteWriteRequest ->
                request.responder.respond(GattResponseStatus.Success, null)
        }
    }

    private inner class LegacyBackendEventSink : PeripheralBackendEventSink {
        override fun onSessionOpened(
            sessionId: PeripheralSessionId,
            maximumUpdateValueLength: Int?,
        ) {
            synchronized(lock) {
                activeSessions += sessionId
                subscriptions.putIfAbsent(sessionId, emptySet())
            }
        }

        override fun onSessionClosed(sessionId: PeripheralSessionId, cause: Throwable?) {
            synchronized(lock) {
                activeSessions -= sessionId
                subscriptions.remove(sessionId)
            }
        }

        override fun onSubscriptionsChanged(
            sessionId: PeripheralSessionId,
            subscriptions: Set<GattCharacteristicId>,
        ) {
            synchronized(lock) {
                this@AndroidBluetoothAdvertiser.subscriptions[sessionId] = subscriptions.toSet()
            }
        }

        override fun onMaximumUpdateValueLengthChanged(
            sessionId: PeripheralSessionId,
            maximumUpdateValueLength: Int?,
        ) = Unit

        override fun onNotificationReady(readiness: NotificationReadiness) = Unit

        override fun onRequest(request: BackendGattServerRequest) {
            handleRequest(request)
        }

        override fun onPlatformFailure(cause: Throwable) {
            logger?.error("AndroidAdvertiser: peripheral platform failure", cause)
        }
    }

    private data class CharacteristicKey(
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
    )

    private data class DescriptorKey(
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val descriptorId: GattDescriptorId,
    )

    private class NotificationUpdate(
        val sessions: List<PeripheralSessionId>,
        val modes: List<NotificationMode>,
        value: ByteArray,
    ) {
        private val copiedValue = value.copyOf()
        val value: ByteArray
            get() = copiedValue.copyOf()
    }

    private companion object {
        fun notificationModes(
            properties: Set<CharacteristicProperty>,
        ): List<NotificationMode> = buildList {
            if (CharacteristicProperty.NOTIFY in properties) {
                add(NotificationMode.Notification)
            }
            if (CharacteristicProperty.INDICATE in properties) {
                add(NotificationMode.Indication)
            }
        }

        fun String.toServiceIdOrNull(): GattServiceId? = runCatching {
            GattServiceId(Uuid.parse(this))
        }.getOrNull()

        fun String.toCharacteristicIdOrNull(): GattCharacteristicId? = runCatching {
            GattCharacteristicId(Uuid.parse(this))
        }.getOrNull()

        fun ByteArray.sliceFrom(offset: Int): ByteArray =
            if (offset in indices) copyOfRange(offset, size) else ByteArray(0)

        fun ByteArray.writtenAt(offset: Int, value: ByteArray): ByteArray {
            if (offset <= 0) return value.copyOf()
            val result = copyOf(maxOf(size, offset + value.size))
            value.copyInto(result, destinationOffset = offset)
            return result
        }
    }
}

@Deprecated(
    message = "Use createBlueFalconPeripheral for production peripheral-role BLE",
    replaceWith = ReplaceWith("createBlueFalconPeripheral(context, logger)"),
)
fun createBluetoothAdvertiser(
    context: Context,
    logger: Logger? = null,
): BluetoothAdvertiser = try {
    val stack = FrameworkAndroidBluetoothStack(context, logger)
    if (stack.capabilities.connectableAdvertising) {
        @Suppress("DEPRECATION")
        AndroidBluetoothAdvertiser(stack, logger)
    } else {
        NoOpBluetoothAdvertiser()
    }
} catch (_: Exception) {
    NoOpBluetoothAdvertiser()
}

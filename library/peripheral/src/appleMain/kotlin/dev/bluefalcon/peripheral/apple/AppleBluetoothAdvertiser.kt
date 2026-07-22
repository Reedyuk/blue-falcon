package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.AdvertiserState
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.CharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattCharacteristicReadRequest
import dev.bluefalcon.peripheral.GattCharacteristicWrite
import dev.bluefalcon.peripheral.GattCharacteristicWriteBatchRequest
import dev.bluefalcon.peripheral.GattCharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralManagerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@Deprecated(
    message = "Use BlueFalconPeripheral for explicit sessions, requests, and backpressure",
    replaceWith = ReplaceWith("createBlueFalconPeripheral(logger)"),
)
class AppleBluetoothAdvertiser private constructor(
    private val peripheral: BlueFalconPeripheral,
    private val logger: Logger?,
    coroutineContext: CoroutineContext,
) : BluetoothAdvertiser {

    constructor(logger: Logger? = null) : this(
        peripheral = createBlueFalconPeripheral(logger, EmptyCoroutineContext),
        logger = logger,
        coroutineContext = EmptyCoroutineContext,
    )

    internal constructor(
        stack: ApplePeripheralStack,
        logger: Logger? = null,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ) : this(
        peripheral = createBlueFalconPeripheral(stack, logger, coroutineContext),
        logger = logger,
        coroutineContext = coroutineContext,
    )

    private val lifecycleMutex = Mutex()
    private val valueMutex = Mutex()
    private val scope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
    private var stateCollector: Job? = null
    private var requestCollector: Job? = null
    private var currentConfig: AdvertiseConfig? = null
    private val values = mutableMapOf<CharacteristicKey, ByteArray>()

    private val mutableState = MutableStateFlow(AdvertiserState.Idle)
    override val state: StateFlow<AdvertiserState> = mutableState.asStateFlow()

    private val mutableWriteRequests =
        MutableSharedFlow<CharacteristicWriteRequest>(extraBufferCapacity = 64)
    override val characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest> =
        mutableWriteRequests

    override suspend fun startAdvertising(config: AdvertiseConfig) = lifecycleMutex.withLock {
        stopAdvertisingLocked()
        currentConfig = config
        valueMutex.withLock {
            values.clear()
            config.services.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    values[
                        CharacteristicKey(
                            GattServiceId(service.uuid.toUuid()),
                            GattCharacteristicId(characteristic.uuid.toUuid()),
                        )
                    ] = characteristic.initialValue?.copyOf() ?: ByteArray(0)
                }
            }
        }
        startCollectors()
        try {
            peripheral.start(PeripheralConfig(config))
            mutableState.value = AdvertiserState.Advertising
        } catch (cause: Throwable) {
            mutableState.value = AdvertiserState.Error
            stopCollectors()
            throw cause
        }
    }

    override suspend fun stopAdvertising() = lifecycleMutex.withLock {
        stopAdvertisingLocked()
    }

    override suspend fun updateCharacteristicValue(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
    ) {
        val serviceId = GattServiceId(serviceUuid.toUuid())
        val characteristicId = GattCharacteristicId(characteristicUuid.toUuid())
        val key = CharacteristicKey(serviceId, characteristicId)
        val mode = currentConfig
            ?.services
            ?.firstOrNull { GattServiceId(it.uuid.toUuid()) == serviceId }
            ?.characteristics
            ?.firstOrNull { GattCharacteristicId(it.uuid.toUuid()) == characteristicId }
            ?.properties
            ?.notificationMode()
        valueMutex.withLock { values[key] = value.copyOf() }
        if (mode == null) return

        peripheral.sessions.value
            .filter { characteristicId in it.subscriptions.value }
            .forEach { session ->
                when (val result = session.notify(characteristicId, value, mode)) {
                    NotificationResult.Sent -> Unit
                    NotificationResult.Busy -> logger?.debug(
                        "Apple legacy advertiser notification queue is busy for ${session.id.value}",
                    )
                    NotificationResult.Disconnected -> logger?.debug(
                        "Apple legacy advertiser session disconnected: ${session.id.value}",
                    )
                    NotificationResult.Unsupported -> logger?.warn(
                        "Apple legacy advertiser notification is unsupported for $characteristicUuid",
                    )
                    is NotificationResult.Failed -> logger?.warn(
                        "Apple legacy advertiser notification failed for $characteristicUuid",
                        result.cause,
                    )
                }
            }
    }

    private fun startCollectors() {
        stateCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peripheral.state.collect { managerState ->
                mutableState.value = when (managerState) {
                    PeripheralManagerState.Running -> AdvertiserState.Advertising
                    is PeripheralManagerState.Failed,
                    PeripheralManagerState.Closed,
                    -> AdvertiserState.Error
                    PeripheralManagerState.Stopped,
                    PeripheralManagerState.Starting,
                    PeripheralManagerState.Stopping,
                    -> AdvertiserState.Idle
                }
            }
        }
        requestCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peripheral.requests.collect(::handleRequest)
        }
    }

    private suspend fun stopAdvertisingLocked() {
        try {
            peripheral.stop()
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            logger?.warn("Apple legacy advertiser stop failed", cause)
        }
        stopCollectors()
        currentConfig = null
        valueMutex.withLock { values.clear() }
        mutableState.value = AdvertiserState.Idle
    }

    private suspend fun stopCollectors() {
        stateCollector?.cancelAndJoin()
        requestCollector?.cancelAndJoin()
        stateCollector = null
        requestCollector = null
    }

    private suspend fun handleRequest(request: GattServerRequest) {
        when (request) {
            is GattCharacteristicReadRequest -> respondToRead(request)
            is GattCharacteristicWriteRequest -> applySingleWrite(request)
            is GattCharacteristicWriteBatchRequest -> applyWriteBatch(request)
            else -> request.response?.respond(GattResponseStatus.RequestNotSupported)
        }
    }

    private suspend fun respondToRead(request: GattCharacteristicReadRequest) {
        val value = valueMutex.withLock {
            values[CharacteristicKey(request.serviceId, request.characteristicId)]?.copyOf()
        }
        when {
            value == null -> request.response.respond(GattResponseStatus.InvalidHandle)
            request.offset !in 0..value.size ->
                request.response.respond(GattResponseStatus.InvalidOffset)
            else -> request.response.respond(
                GattResponseStatus.Success,
                value.copyOfRange(request.offset, value.size),
            )
        }
    }

    private suspend fun applySingleWrite(request: GattCharacteristicWriteRequest) {
        val applied = valueMutex.withLock {
            val key = CharacteristicKey(request.serviceId, request.characteristicId)
            val current = values[key] ?: return@withLock false
            val merged = current.merge(request.offset, request.value) ?: return@withLock false
            values[key] = merged
            true
        }
        if (!applied) {
            request.response?.respond(GattResponseStatus.InvalidOffset)
            return
        }
        emitLegacyWrite(request.serviceId, request.characteristicId, request.value)
        request.response?.respond(GattResponseStatus.Success)
    }

    private suspend fun applyWriteBatch(request: GattCharacteristicWriteBatchRequest) {
        val writes = request.writes
        val applied = valueMutex.withLock {
            val updated = values.mapValuesTo(mutableMapOf()) { (_, value) -> value.copyOf() }
            for (write in writes) {
                val key = CharacteristicKey(write.serviceId, write.characteristicId)
                val current = updated[key] ?: return@withLock false
                updated[key] = current.merge(write.offset, write.value)
                    ?: return@withLock false
            }
            values.clear()
            values.putAll(updated)
            true
        }
        if (!applied) {
            request.response.respond(GattResponseStatus.InvalidOffset)
            return
        }
        writes.forEach { write ->
            emitLegacyWrite(write.serviceId, write.characteristicId, write.value)
        }
        request.response.respond(GattResponseStatus.Success)
    }

    private suspend fun emitLegacyWrite(
        serviceId: GattServiceId,
        characteristicId: GattCharacteristicId,
        value: ByteArray,
    ) {
        mutableWriteRequests.emit(
            CharacteristicWriteRequest(
                serviceUuid = serviceId.uuid.toString(),
                characteristicUuid = characteristicId.uuid.toString(),
                value = value.copyOf(),
            ),
        )
    }

    private fun ByteArray.merge(offset: Int, update: ByteArray): ByteArray? {
        if (offset !in 0..size) return null
        return copyOf(maxOf(size, offset + update.size)).also { merged ->
            update.copyInto(merged, destinationOffset = offset)
        }
    }

    private fun Set<CharacteristicProperty>.notificationMode(): NotificationMode? = when {
        CharacteristicProperty.NOTIFY in this -> NotificationMode.Notification
        CharacteristicProperty.INDICATE in this -> NotificationMode.Indication
        else -> null
    }

    private data class CharacteristicKey(
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
    )
}

@Deprecated(
    message = "Use createBlueFalconPeripheral for explicit sessions, requests, and backpressure",
    replaceWith = ReplaceWith("createBlueFalconPeripheral(logger)"),
)
@Suppress("DEPRECATION")
fun createBluetoothAdvertiser(logger: Logger? = null): BluetoothAdvertiser =
    AppleBluetoothAdvertiser(logger)

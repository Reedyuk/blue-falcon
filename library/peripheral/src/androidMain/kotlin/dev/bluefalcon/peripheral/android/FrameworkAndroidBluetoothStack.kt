package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.bluefalcon.core.Logger
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorConfig
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID as JavaUuid
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class FrameworkAndroidBluetoothStack(
    context: Context,
    private val logger: Logger?,
) : AndroidBluetoothStack {
    private val applicationContext = context.applicationContext
    private val bluetoothManager =
        applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val lock = Any()

    private var openingGattServer = false
    private var gattServer: BluetoothGattServer? = null
    private var pendingService: PendingService? = null
    private var currentAdvertiser: BluetoothLeAdvertiser? = null
    private var currentAdvertiseCallback: AdvertiseCallback? = null
    private var pendingAdvertising: CompletableDeferred<Unit>? = null
    private val devicesBySession = mutableMapOf<PeripheralSessionId, BluetoothDevice>()
    private val characteristicsById =
        mutableMapOf<GattCharacteristicId, BluetoothGattCharacteristic>()

    override val capabilities: AndroidStackCapabilities
        get() {
            val adapter = bluetoothManager.adapter
            return AndroidStackCapabilities(
                localGattServer = adapter != null,
                connectableAdvertising = adapter?.isMultipleAdvertisementSupported == true,
            )
        }

    override suspend fun open(listener: AndroidBluetoothStackListener) =
        openGattServer(listener)

    override suspend fun addService(service: GattServiceConfig) =
        addServiceAndAwaitCallback(service)

    override suspend fun startAdvertising(config: AdvertiseConfig) =
        startAdvertisingAndAwaitCallback(config)

    override fun sendResponse(response: AndroidGattResponse): Boolean =
        sendTargetedResponse(response)

    override fun notify(request: AndroidNotificationRequest): AndroidNotificationStartResult =
        sendTargetedNotification(request)

    override fun disconnect(sessionId: PeripheralSessionId): Boolean =
        cancelTargetedConnection(sessionId)

    override fun stopAdvertising() = stopCurrentAdvertisement()

    override fun closeGattServer() = closeCurrentGattServer()

    private fun openGattServer(listener: AndroidBluetoothStackListener) {
        synchronized(lock) {
            check(!openingGattServer && gattServer == null) { "Android GATT server is already open" }
            openingGattServer = true
        }

        val callback = createGattServerCallback(listener)
        val openedServer = try {
            bluetoothManager.openGattServer(applicationContext, callback)
        } catch (cause: Throwable) {
            synchronized(lock) { openingGattServer = false }
            throw cause
        }

        if (openedServer == null) {
            synchronized(lock) { openingGattServer = false }
            throw IllegalStateException("Android failed to open a GATT server")
        }

        val accepted = synchronized(lock) {
            if (openingGattServer) {
                openingGattServer = false
                gattServer = openedServer
                true
            } else {
                false
            }
        }
        if (!accepted) {
            openedServer.close()
            throw CancellationException("Android GATT server opening was cancelled")
        }
    }

    private suspend fun addServiceAndAwaitCallback(config: GattServiceConfig) {
        val built = buildService(config)
        val completion = CompletableDeferred<Unit>()
        val server = synchronized(lock) {
            check(pendingService == null) { "Another Android GATT service is still being added" }
            val currentServer = checkNotNull(gattServer) { "Android GATT server is not open" }
            pendingService = PendingService(built, completion)
            currentServer
        }

        val accepted = try {
            server.addService(built.service)
        } catch (cause: Throwable) {
            clearPendingService(completion)
            throw cause
        }
        if (!accepted) {
            clearPendingService(completion)
            throw IllegalStateException("Android rejected GATT service ${config.uuid}")
        }

        try {
            completion.await()
        } catch (cause: Throwable) {
            clearPendingService(completion)
            throw cause
        }
    }

    private suspend fun startAdvertisingAndAwaitCallback(config: AdvertiseConfig) {
        val adapter = bluetoothManager.adapter
            ?: throw IllegalStateException("Android Bluetooth adapter is unavailable")
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: throw IllegalStateException("Android BLE advertising is unavailable")
        val completion = CompletableDeferred<Unit>()
        val callback = createAdvertiseCallback(completion)

        synchronized(lock) {
            check(currentAdvertiseCallback == null) { "Android BLE advertising is already active" }
            currentAdvertiser = advertiser
            currentAdvertiseCallback = callback
            pendingAdvertising = completion
        }

        try {
            advertiser.startAdvertising(
                config.toAdvertiseSettings(),
                config.toAdvertiseData(),
                callback,
            )
            completion.await()
        } catch (cause: Throwable) {
            val shouldStop = clearAdvertisementIfCurrent(callback)
            if (shouldStop) {
                runCatching { advertiser.stopAdvertising(callback) }
                    .onFailure { logger?.warn("Failed to stop Android BLE advertising", it) }
            }
            throw cause
        }
    }

    private fun sendTargetedResponse(response: AndroidGattResponse): Boolean {
        val target = synchronized(lock) {
            val server = gattServer ?: return@synchronized null
            val device = devicesBySession[response.sessionId] ?: return@synchronized null
            server to device
        } ?: return false

        return try {
            target.first.sendResponse(
                target.second,
                response.requestId,
                response.status.toAndroidGattStatus(),
                response.offset,
                response.value,
            )
        } catch (cause: Throwable) {
            logger?.warn("Failed to send Android GATT response", cause)
            false
        }
    }

    private fun sendTargetedNotification(
        request: AndroidNotificationRequest,
    ): AndroidNotificationStartResult {
        val target = synchronized(lock) {
            val server = gattServer ?: return@synchronized null
            val device = devicesBySession[request.sessionId] ?: return@synchronized null
            val characteristic = characteristicsById[request.characteristicId]
                ?: return@synchronized null
            NotificationTarget(server, device, characteristic)
        } ?: return AndroidNotificationStartResult.Rejected(
            IllegalStateException("Android notification target is unavailable"),
        )

        return try {
            val value = request.value
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.server.notifyCharacteristicChanged(
                    target.device,
                    target.characteristic,
                    request.mode.toAndroidConfirm(),
                    value,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                target.characteristic.value = value.copyOf()
                @Suppress("DEPRECATION")
                target.server.notifyCharacteristicChanged(
                    target.device,
                    target.characteristic,
                    request.mode.toAndroidConfirm(),
                )
            }
            if (accepted) {
                AndroidNotificationStartResult.Accepted
            } else {
                AndroidNotificationStartResult.Rejected(
                    IllegalStateException("Android rejected the GATT notification"),
                )
            }
        } catch (cause: Throwable) {
            AndroidNotificationStartResult.Rejected(cause)
        }
    }

    private fun cancelTargetedConnection(sessionId: PeripheralSessionId): Boolean {
        val target = synchronized(lock) {
            val server = gattServer ?: return@synchronized null
            val device = devicesBySession[sessionId] ?: return@synchronized null
            server to device
        } ?: return false

        return try {
            target.first.cancelConnection(target.second)
            true
        } catch (cause: Throwable) {
            logger?.warn("Failed to disconnect Android GATT session ${sessionId.value}", cause)
            false
        }
    }

    private fun stopCurrentAdvertisement() {
        val stopped = synchronized(lock) {
            val advertiser = currentAdvertiser
            val callback = currentAdvertiseCallback
            val completion = pendingAdvertising
            currentAdvertiser = null
            currentAdvertiseCallback = null
            pendingAdvertising = null
            StoppedAdvertisement(advertiser, callback, completion)
        }

        stopped.completion?.completeExceptionally(
            CancellationException("Android BLE advertising was stopped"),
        )
        if (stopped.advertiser != null && stopped.callback != null) {
            runCatching { stopped.advertiser.stopAdvertising(stopped.callback) }
                .onFailure { logger?.warn("Failed to stop Android BLE advertising", it) }
        }
    }

    private fun closeCurrentGattServer() {
        stopCurrentAdvertisement()
        val closed = synchronized(lock) {
            openingGattServer = false
            val server = gattServer
            val serviceCompletion = pendingService?.completion
            gattServer = null
            pendingService = null
            devicesBySession.clear()
            characteristicsById.clear()
            ClosedGattServer(server, serviceCompletion)
        }

        closed.serviceCompletion?.completeExceptionally(
            CancellationException("Android GATT server was closed"),
        )
        closed.server?.let { server ->
            runCatching { server.close() }
                .onFailure { logger?.warn("Failed to close Android GATT server", it) }
        }
    }

    private fun createGattServerCallback(
        listener: AndroidBluetoothStackListener,
    ): BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            completePendingService(status, service)
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            val sessionId = device.toSessionId()
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    rememberDevice(sessionId, device)
                    listener.onEvent(AndroidGattEvent.Connected(sessionId))
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(lock) { devicesBySession.remove(sessionId) }
                    listener.onEvent(AndroidGattEvent.Disconnected(sessionId, status))
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val sessionId = rememberDevice(device)
            listener.onEvent(AndroidGattEvent.MtuChanged(sessionId, mtu))
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val sessionId = rememberDevice(device)
            val identity = characteristic.identityOrNull() ?: return
            listener.onEvent(
                AndroidGattEvent.CharacteristicRead(
                    sessionId = sessionId,
                    requestId = requestId,
                    serviceId = identity.serviceId,
                    characteristicId = identity.characteristicId,
                    offset = offset,
                ),
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val sessionId = rememberDevice(device)
            val identity = characteristic.identityOrNull() ?: return
            listener.onEvent(
                AndroidGattEvent.CharacteristicWrite(
                    sessionId = sessionId,
                    requestId = requestId,
                    serviceId = identity.serviceId,
                    characteristicId = identity.characteristicId,
                    offset = offset,
                    preparedWrite = preparedWrite,
                    responseNeeded = responseNeeded,
                    value = value ?: ByteArray(0),
                ),
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val sessionId = rememberDevice(device)
            val identity = descriptor.identityOrNull() ?: return
            listener.onEvent(
                AndroidGattEvent.DescriptorRead(
                    sessionId = sessionId,
                    requestId = requestId,
                    serviceId = identity.serviceId,
                    characteristicId = identity.characteristicId,
                    descriptorId = identity.descriptorId,
                    offset = offset,
                ),
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val sessionId = rememberDevice(device)
            val identity = descriptor.identityOrNull() ?: return
            listener.onEvent(
                AndroidGattEvent.DescriptorWrite(
                    sessionId = sessionId,
                    requestId = requestId,
                    serviceId = identity.serviceId,
                    characteristicId = identity.characteristicId,
                    descriptorId = identity.descriptorId,
                    offset = offset,
                    preparedWrite = preparedWrite,
                    responseNeeded = responseNeeded,
                    value = value ?: ByteArray(0),
                ),
            )
        }

        override fun onExecuteWrite(
            device: BluetoothDevice,
            requestId: Int,
            execute: Boolean,
        ) {
            val sessionId = rememberDevice(device)
            listener.onEvent(AndroidGattEvent.ExecuteWrite(sessionId, requestId, execute))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val sessionId = rememberDevice(device)
            listener.onEvent(AndroidGattEvent.NotificationSent(sessionId, status))
        }
    }

    private fun createAdvertiseCallback(
        completion: CompletableDeferred<Unit>,
    ): AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            val pending = synchronized(lock) {
                if (currentAdvertiseCallback === this && pendingAdvertising === completion) {
                    pendingAdvertising = null
                    completion
                } else {
                    null
                }
            }
            pending?.complete(Unit)
        }

        override fun onStartFailure(errorCode: Int) {
            val pending = synchronized(lock) {
                if (currentAdvertiseCallback === this && pendingAdvertising === completion) {
                    currentAdvertiser = null
                    currentAdvertiseCallback = null
                    pendingAdvertising = null
                    completion
                } else {
                    null
                }
            }
            pending?.completeExceptionally(
                IllegalStateException("Android BLE advertising failed with code $errorCode"),
            )
        }
    }

    private fun completePendingService(status: Int, service: BluetoothGattService) {
        val pending = synchronized(lock) {
            val current = pendingService
            if (current != null && current.built.service.uuid == service.uuid) {
                pendingService = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    characteristicsById.putAll(current.built.characteristics)
                }
                current
            } else {
                null
            }
        } ?: return

        if (status == BluetoothGatt.GATT_SUCCESS) {
            pending.completion.complete(Unit)
        } else {
            pending.completion.completeExceptionally(
                IllegalStateException(
                    "Android failed to add GATT service ${service.uuid} with status $status",
                ),
            )
        }
    }

    private fun clearPendingService(completion: CompletableDeferred<Unit>) {
        synchronized(lock) {
            if (pendingService?.completion === completion) {
                pendingService = null
            }
        }
    }

    private fun clearAdvertisementIfCurrent(callback: AdvertiseCallback): Boolean =
        synchronized(lock) {
            if (currentAdvertiseCallback === callback) {
                currentAdvertiser = null
                currentAdvertiseCallback = null
                pendingAdvertising = null
                true
            } else {
                false
            }
        }

    private fun rememberDevice(device: BluetoothDevice): PeripheralSessionId {
        val sessionId = device.toSessionId()
        rememberDevice(sessionId, device)
        return sessionId
    }

    private fun rememberDevice(sessionId: PeripheralSessionId, device: BluetoothDevice) {
        synchronized(lock) { devicesBySession[sessionId] = device }
    }

    private fun buildService(config: GattServiceConfig): BuiltService {
        val service = BluetoothGattService(
            Uuid.parse(config.uuid).toJavaUuid(),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val characteristics = mutableMapOf<GattCharacteristicId, BluetoothGattCharacteristic>()

        config.characteristics.forEach { characteristicConfig ->
            val characteristicId = GattCharacteristicId(Uuid.parse(characteristicConfig.uuid))
            val characteristic = BluetoothGattCharacteristic(
                characteristicId.uuid.toJavaUuid(),
                characteristicConfig.properties.toAndroidProperties(),
                characteristicConfig.toAndroidPermissions(),
            )
            characteristicConfig.initialValue?.let { initialValue ->
                @Suppress("DEPRECATION")
                characteristic.value = initialValue.copyOf()
            }
            characteristicConfig.descriptors.forEach { descriptorConfig ->
                characteristic.addDescriptor(descriptorConfig.toBluetoothGattDescriptor())
            }
            characteristicConfig.addCccdIfRequired(characteristic)
            check(service.addCharacteristic(characteristic)) {
                "Android rejected GATT characteristic ${characteristicConfig.uuid}"
            }
            characteristics[characteristicId] = characteristic
        }

        return BuiltService(service, characteristics)
    }

    private fun GattDescriptorConfig.toBluetoothGattDescriptor(): BluetoothGattDescriptor =
        BluetoothGattDescriptor(
            Uuid.parse(uuid).toJavaUuid(),
            toAndroidPermissions(),
        ).also { descriptor ->
            initialValue?.let { initialValue ->
                @Suppress("DEPRECATION")
                descriptor.value = initialValue.copyOf()
            }
        }

    private fun GattCharacteristicConfig.addCccdIfRequired(
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (CharacteristicProperty.NOTIFY !in properties &&
            CharacteristicProperty.INDICATE !in properties
        ) {
            return
        }
        val cccdUuid = Uuid.parse(CCCD_UUID).toJavaUuid()
        if (characteristic.getDescriptor(cccdUuid) != null) return

        val cccd = BluetoothGattDescriptor(
            cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.copyOf()
        characteristic.addDescriptor(cccd)
    }

    private fun AdvertiseConfig.toAdvertiseSettings(): AdvertiseSettings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(services.isNotEmpty())
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

    private fun AdvertiseConfig.toAdvertiseData(): AdvertiseData =
        AdvertiseData.Builder().apply {
            setIncludeDeviceName(localName != null)
            setIncludeTxPowerLevel(includeTxPower)
            serviceUuids.forEach { uuid ->
                addServiceUuid(ParcelUuid(Uuid.parse(uuid).toJavaUuid()))
            }
            manufacturerData.forEach { (id, data) ->
                addManufacturerData(id, data.copyOf())
            }
        }.build()

    private fun BluetoothDevice.toSessionId(): PeripheralSessionId =
        PeripheralSessionId(address)

    private fun BluetoothGattCharacteristic.identityOrNull(): CharacteristicIdentity? {
        val serviceUuid = service?.uuid ?: return null
        return CharacteristicIdentity(
            serviceId = GattServiceId(serviceUuid.toKotlinUuid()),
            characteristicId = GattCharacteristicId(uuid.toKotlinUuid()),
        )
    }

    private fun BluetoothGattDescriptor.identityOrNull(): DescriptorIdentity? {
        val characteristic = characteristic ?: return null
        val characteristicIdentity = characteristic.identityOrNull() ?: return null
        return DescriptorIdentity(
            serviceId = characteristicIdentity.serviceId,
            characteristicId = characteristicIdentity.characteristicId,
            descriptorId = GattDescriptorId(uuid.toKotlinUuid()),
        )
    }

    private data class PendingService(
        val built: BuiltService,
        val completion: CompletableDeferred<Unit>,
    )

    private data class BuiltService(
        val service: BluetoothGattService,
        val characteristics: Map<GattCharacteristicId, BluetoothGattCharacteristic>,
    )

    private data class NotificationTarget(
        val server: BluetoothGattServer,
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
    )

    private data class StoppedAdvertisement(
        val advertiser: BluetoothLeAdvertiser?,
        val callback: AdvertiseCallback?,
        val completion: CompletableDeferred<Unit>?,
    )

    private data class ClosedGattServer(
        val server: BluetoothGattServer?,
        val serviceCompletion: CompletableDeferred<Unit>?,
    )

    private data class CharacteristicIdentity(
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
    )

    private data class DescriptorIdentity(
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val descriptorId: GattDescriptorId,
    )

    private companion object {
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}

internal fun GattResponseStatus.toAndroidGattStatus(): Int = when (this) {
    GattResponseStatus.Success -> BluetoothGatt.GATT_SUCCESS
    GattResponseStatus.InvalidHandle -> 0x01
    GattResponseStatus.ReadNotPermitted -> BluetoothGatt.GATT_READ_NOT_PERMITTED
    GattResponseStatus.WriteNotPermitted -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
    GattResponseStatus.InvalidOffset -> BluetoothGatt.GATT_INVALID_OFFSET
    GattResponseStatus.InvalidAttributeValueLength -> 0x0d
    GattResponseStatus.InsufficientAuthentication -> BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
    GattResponseStatus.InsufficientAuthorization -> 0x08
    GattResponseStatus.InsufficientEncryption -> 0x0f
    GattResponseStatus.RequestNotSupported -> 0x06
    GattResponseStatus.PrepareQueueFull -> 0x09
    GattResponseStatus.UnlikelyError -> BluetoothGatt.GATT_FAILURE
}

internal fun Set<CharacteristicProperty>.toAndroidProperties(): Int =
    fold(0) { properties, property ->
        properties or when (property) {
            CharacteristicProperty.READ -> BluetoothGattCharacteristic.PROPERTY_READ
            CharacteristicProperty.WRITE -> BluetoothGattCharacteristic.PROPERTY_WRITE
            CharacteristicProperty.WRITE_NO_RESPONSE ->
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            CharacteristicProperty.NOTIFY -> BluetoothGattCharacteristic.PROPERTY_NOTIFY
            CharacteristicProperty.INDICATE -> BluetoothGattCharacteristic.PROPERTY_INDICATE
            CharacteristicProperty.SIGNED_WRITE -> BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
            CharacteristicProperty.EXTENDED_PROPERTIES ->
                BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
        }
    }

internal fun Set<CharacteristicProperty>.toAndroidPermissions(): Int =
    fold(0) { permissions, property ->
        permissions or when (property) {
            CharacteristicProperty.READ -> BluetoothGattCharacteristic.PERMISSION_READ
            CharacteristicProperty.WRITE,
            CharacteristicProperty.WRITE_NO_RESPONSE,
            CharacteristicProperty.SIGNED_WRITE,
            -> BluetoothGattCharacteristic.PERMISSION_WRITE
            CharacteristicProperty.NOTIFY,
            CharacteristicProperty.INDICATE,
            CharacteristicProperty.EXTENDED_PROPERTIES,
            -> 0
        }
    }

internal fun GattCharacteristicConfig.toAndroidPermissions(): Int =
    permissions.takeIf { it != 0 } ?: properties.toAndroidPermissions()

internal fun GattDescriptorConfig.toAndroidPermissions(): Int =
    permissions.takeIf { it != 0 }
        ?: (BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

internal fun Uuid.toJavaUuid(): JavaUuid = JavaUuid.fromString(toString())

@OptIn(ExperimentalUuidApi::class)
internal fun JavaUuid.toKotlinUuid(): Uuid = Uuid.parse(toString())

internal fun NotificationMode.toAndroidConfirm(): Boolean =
    this == NotificationMode.Indication

package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorConfig
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBATTError
import platform.CoreBluetooth.CBATTErrorInsufficientAuthentication
import platform.CoreBluetooth.CBATTErrorInsufficientAuthorization
import platform.CoreBluetooth.CBATTErrorInsufficientEncryption
import platform.CoreBluetooth.CBATTErrorInvalidAttributeValueLength
import platform.CoreBluetooth.CBATTErrorInvalidHandle
import platform.CoreBluetooth.CBATTErrorInvalidOffset
import platform.CoreBluetooth.CBATTErrorPrepareQueueFull
import platform.CoreBluetooth.CBATTErrorReadNotPermitted
import platform.CoreBluetooth.CBATTErrorRequestNotSupported
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTErrorUnlikelyError
import platform.CoreBluetooth.CBATTErrorWriteNotPermitted
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissions
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCharacteristicProperties
import platform.CoreBluetooth.CBCharacteristicPropertyAuthenticatedSignedWrites
import platform.CoreBluetooth.CBCharacteristicPropertyExtendedProperties
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBUUID
import platform.CoreBluetooth.CBATTErrorAttributeNotFound
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableDescriptor
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBPeripheralManagerRestoredStateAdvertisementDataKey
import platform.CoreBluetooth.CBPeripheralManagerRestoredStateServicesKey
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
internal fun GattResponseStatus.toAppleAttError(): CBATTError = when (this) {
    GattResponseStatus.Success -> CBATTErrorSuccess
    GattResponseStatus.InvalidHandle -> CBATTErrorInvalidHandle
    GattResponseStatus.ReadNotPermitted -> CBATTErrorReadNotPermitted
    GattResponseStatus.WriteNotPermitted -> CBATTErrorWriteNotPermitted
    GattResponseStatus.InvalidOffset -> CBATTErrorInvalidOffset
    GattResponseStatus.InvalidAttributeValueLength -> CBATTErrorInvalidAttributeValueLength
    GattResponseStatus.InsufficientAuthentication -> CBATTErrorInsufficientAuthentication
    GattResponseStatus.InsufficientAuthorization -> CBATTErrorInsufficientAuthorization
    GattResponseStatus.InsufficientEncryption -> CBATTErrorInsufficientEncryption
    GattResponseStatus.RequestNotSupported -> CBATTErrorRequestNotSupported
    GattResponseStatus.PrepareQueueFull -> CBATTErrorPrepareQueueFull
    GattResponseStatus.UnlikelyError -> CBATTErrorUnlikelyError
}

@OptIn(ExperimentalForeignApi::class)
internal fun Set<CharacteristicProperty>.toAppleProperties(): CBCharacteristicProperties =
    fold(0UL) { properties, property ->
        properties or when (property) {
            CharacteristicProperty.READ -> CBCharacteristicPropertyRead
            CharacteristicProperty.WRITE -> CBCharacteristicPropertyWrite
            CharacteristicProperty.WRITE_NO_RESPONSE ->
                CBCharacteristicPropertyWriteWithoutResponse
            CharacteristicProperty.NOTIFY -> CBCharacteristicPropertyNotify
            CharacteristicProperty.INDICATE -> CBCharacteristicPropertyIndicate
            CharacteristicProperty.SIGNED_WRITE ->
                CBCharacteristicPropertyAuthenticatedSignedWrites
            CharacteristicProperty.EXTENDED_PROPERTIES ->
                CBCharacteristicPropertyExtendedProperties
        }
    }

@OptIn(ExperimentalForeignApi::class)
internal fun GattCharacteristicConfig.toApplePermissions(): CBAttributePermissions {
    if (permissions != 0) return permissions.toULong()
    return properties.fold(0UL) { result, property ->
        result or when (property) {
            CharacteristicProperty.READ -> CBAttributePermissionsReadable
            CharacteristicProperty.WRITE,
            CharacteristicProperty.WRITE_NO_RESPONSE,
            CharacteristicProperty.SIGNED_WRITE,
            -> CBAttributePermissionsWriteable
            CharacteristicProperty.NOTIFY,
            CharacteristicProperty.INDICATE,
            CharacteristicProperty.EXTENDED_PROPERTIES,
            -> 0UL
        }
    }
}

internal fun Set<CharacteristicProperty>.toAppleNotificationModes(): Set<NotificationMode> =
    buildSet {
        if (CharacteristicProperty.NOTIFY in this@toAppleNotificationModes) {
            add(NotificationMode.Notification)
        }
        if (CharacteristicProperty.INDICATE in this@toAppleNotificationModes) {
            add(NotificationMode.Indication)
        }
    }

internal fun isAppleManagedCccd(uuid: String): Boolean = when (uuid.uppercase()) {
    "2902", "00002902-0000-1000-8000-00805F9B34FB" -> true
    else -> false
}

@OptIn(ExperimentalForeignApi::class)
internal fun AdvertiseConfig.toAppleAdvertisementData(): Map<Any?, Any?> = buildMap {
    localName?.let { put(CBAdvertisementDataLocalNameKey, it) }
    if (serviceUuids.isNotEmpty()) {
        put(
            CBAdvertisementDataServiceUUIDsKey,
            serviceUuids.map(CBUUID::UUIDWithString),
        )
    }
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal fun GattServiceConfig.toAppleMutableService(): CBMutableService {
    val service = CBMutableService(CBUUID.UUIDWithString(uuid), true)
    val mutableCharacteristics = characteristics.map { characteristicConfig ->
        CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(characteristicConfig.uuid),
            properties = characteristicConfig.properties.toAppleProperties(),
            value = null,
            permissions = characteristicConfig.toApplePermissions(),
        ).also { characteristic ->
            val descriptors = characteristicConfig.descriptors
                .filterNot { isAppleManagedCccd(it.uuid) }
                .map { descriptor ->
                    CBMutableDescriptor(
                        CBUUID.UUIDWithString(descriptor.uuid),
                        descriptor.toAppleDescriptorValue(),
                    )
                }
            if (descriptors.isNotEmpty()) characteristic.setDescriptors(descriptors)
        }
    }
    service.setCharacteristics(mutableCharacteristics)
    return service
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal class FrameworkApplePeripheralStack(
    private val logger: Logger? = null,
    private val operationTimeout: Duration = 10.seconds,
) : ApplePeripheralStack {
    private val stateLock = NSLock()
    private val delegateQueue = dispatch_queue_create(
        "dev.bluefalcon.peripheral.apple",
        null,
    )
    private var manager: CBPeripheralManager? = null
    private var callbackOwner: ApplePeripheralCallbackOwner<CBPeripheralManager>? = null
    private var managerDelegate: CBPeripheralManagerDelegateProtocol? = null
    private var closed = false
    private var poweredOnWaiter: CompletableDeferred<Unit>? = null
    private var advertisingWaiter: CompletableDeferred<Unit>? = null
    private val serviceWaiters = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var restoredState: RestoredState? = null
    private var nextRequestToken = 0L
    private val pendingRequests = mutableMapOf<AppleRequestToken, PendingRequest>()
    private val characteristics = mutableMapOf<GattCharacteristicId, CBMutableCharacteristic>()
    private val centrals = mutableMapOf<PeripheralSessionId, CBCentral>()

    private fun createDelegate(
        owner: ApplePeripheralCallbackOwner<CBPeripheralManager>,
    ): CBPeripheralManagerDelegateProtocol = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            val completion = locked {
                if (!ownsCallbackLocked(owner, peripheral)) return
                when (peripheral.state) {
                    CBManagerStatePoweredOn -> poweredOnWaiter.also { poweredOnWaiter = null }
                    CBManagerStateUnsupported -> poweredOnWaiter.also {
                        poweredOnWaiter = null
                        it?.completeExceptionally(
                            ApplePeripheralUnavailableException("unsupported"),
                        )
                    }
                    CBManagerStateUnauthorized -> poweredOnWaiter.also {
                        poweredOnWaiter = null
                        it?.completeExceptionally(
                            ApplePeripheralUnavailableException("unauthorized"),
                        )
                    }
                    else -> null
                }
            }
            if (peripheral.state == CBManagerStatePoweredOn) completion?.complete(Unit)
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didAddService: CBService,
            error: NSError?,
        ) {
            val waiter = locked {
                if (!ownsCallbackLocked(owner, peripheral)) return
                serviceWaiters.remove(normalizeAppleUuid(didAddService.UUID.UUIDString))
            } ?: return
            if (error == null) {
                waiter.complete(Unit)
            } else {
                waiter.completeExceptionally(
                    AppleServicePublicationException(
                        didAddService.UUID.UUIDString,
                        error.localizedDescription,
                    ),
                )
            }
        }

        override fun peripheralManagerDidStartAdvertising(
            peripheral: CBPeripheralManager,
            error: NSError?,
        ) {
            val waiter = locked {
                if (!ownsCallbackLocked(owner, peripheral)) return
                advertisingWaiter.also { advertisingWaiter = null }
            }
                ?: return
            if (error == null) {
                waiter.complete(Unit)
            } else {
                waiter.completeExceptionally(
                    AppleAdvertisingException(error.localizedDescription),
                )
            }
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic,
        ) {
            if (owner.listenerFor(peripheral) == null) return
            val ids = resolveIds(owner, didSubscribeToCharacteristic) ?: run {
                logger?.debug("didSubscribeToCharacteristic: resolveIds returned null, dropping event")
                return
            }
            logger?.debug("didSubscribeToCharacteristic: resolved ids=$ids for central=${central.identifier}")
            if (!retainCentral(owner, central)) return
            owner.listenerFor(peripheral)?.onEvent(
                AppleGattEvent.Subscribed(
                    sessionId = central.sessionId(),
                    maximumUpdateValueLength = central.maximumUpdateValueLength.toInt(),
                    characteristicId = ids.second,
                ),
            )
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic,
        ) {
            if (owner.listenerFor(peripheral) == null) return
            val ids = resolveIds(owner, didUnsubscribeFromCharacteristic) ?: return
            if (!retainCentral(owner, central)) return
            owner.listenerFor(peripheral)?.onEvent(
                AppleGattEvent.Unsubscribed(
                    sessionId = central.sessionId(),
                    maximumUpdateValueLength = central.maximumUpdateValueLength.toInt(),
                    characteristicId = ids.second,
                ),
            )
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveReadRequest: CBATTRequest,
        ) {
            if (owner.listenerFor(peripheral) == null) return
            val ids = resolveIds(owner, didReceiveReadRequest.characteristic)
            if (ids == null) {
                logger?.debug("didReceiveReadRequest: resolveIds returned null, rejecting request")
                peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorAttributeNotFound)
                return
            }
            val central = didReceiveReadRequest.central
            if (!retainCentral(owner, central)) return
            val token = retainRequest(owner, peripheral, didReceiveReadRequest, read = true) ?: return
            owner.listenerFor(peripheral)?.onEvent(
                AppleGattEvent.CharacteristicRead(
                    sessionId = central.sessionId(),
                    maximumUpdateValueLength = central.maximumUpdateValueLength.toInt(),
                    requestToken = token,
                    serviceId = ids.first,
                    characteristicId = ids.second,
                    offset = didReceiveReadRequest.offset.toInt(),
                ),
            ) ?: rejectPendingRequest(token)
        }

        @Suppress("UNCHECKED_CAST")
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>,
        ) {
            if (owner.listenerFor(peripheral) == null) return
            val requests = didReceiveWriteRequests as List<CBATTRequest>
            val first = requests.firstOrNull() ?: run {
                owner.listenerFor(peripheral)?.onPlatformFailure(
                    AppleEmptyWriteBatchException(),
                )
                return
            }
            val writes = requests.map { request ->
                val ids = resolveIds(owner, request.characteristic)
                    ?: return rejectRequest(peripheral, first, GattResponseStatus.InvalidHandle)
                val value = request.value?.toByteArray()
                    ?: return rejectRequest(
                        peripheral,
                        first,
                        GattResponseStatus.InvalidAttributeValueLength,
                    )
                AppleCharacteristicWrite(
                    serviceId = ids.first,
                    characteristicId = ids.second,
                    offset = request.offset.toInt(),
                    value = value,
                )
            }
            val central = first.central
            if (requests.any { it.central.identifier != central.identifier }) {
                rejectRequest(peripheral, first, GattResponseStatus.UnlikelyError)
                return
            }
            if (!retainCentral(owner, central)) return
            val token = retainRequest(owner, peripheral, first, read = false) ?: return
            val event = if (writes.size == 1) {
                AppleGattEvent.CharacteristicWrite(
                    sessionId = central.sessionId(),
                    maximumUpdateValueLength = central.maximumUpdateValueLength.toInt(),
                    requestToken = token,
                    write = writes.single(),
                )
            } else {
                AppleGattEvent.CharacteristicWriteBatch(
                    sessionId = central.sessionId(),
                    maximumUpdateValueLength = central.maximumUpdateValueLength.toInt(),
                    requestToken = token,
                    writes = writes,
                )
            }
            owner.listenerFor(peripheral)?.onEvent(event) ?: rejectPendingRequest(token)
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(
            peripheral: CBPeripheralManager,
        ) {
            owner.listenerFor(peripheral)?.onEvent(AppleGattEvent.NotificationReady)
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            willRestoreState: Map<Any?, *>,
        ) {
            @Suppress("UNCHECKED_CAST")
            val services = willRestoreState[CBPeripheralManagerRestoredStateServicesKey]
                as? List<CBMutableService> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val advertisementData =
                willRestoreState[CBPeripheralManagerRestoredStateAdvertisementDataKey]
                    as? Map<Any?, Any?>
            locked {
                if (!ownsCallbackLocked(owner, peripheral)) return
                restoredState = RestoredState(services, advertisementData)
            }
        }
    }

    override suspend fun open(
        config: PeripheralConfig,
        listener: ApplePeripheralStackListener,
    ): AppleOpenResult {
        require(config.advertiseConfig.manufacturerData.isEmpty()) {
            "Core Bluetooth peripheral advertising does not support manufacturerData"
        }
        require(!config.advertiseConfig.includeTxPower) {
            "Core Bluetooth peripheral advertising does not support includeTxPower"
        }
        val peripheral = prepareManager(config, listener)
        awaitPoweredOn(peripheral)

        val restoration = locked { restoredState.also { restoredState = null } }
        val adopted = restoration?.services?.takeIf {
            topology(it) == topology(config.advertiseConfig.services)
        }
        if (adopted == null) {
            peripheral.removeAllServices()
            clearHostedState()
            publishConfiguredServices(peripheral, config.advertiseConfig.services)
        } else {
            adoptServices(adopted)
        }

        val restoredSessions = adopted?.let(::restoredSessions).orEmpty()
        if (restoration?.advertisementData != null) {
            logger?.debug("Apple peripheral restored advertisement state")
        }
        if (!peripheral.isAdvertising) {
            val waiter = CompletableDeferred<Unit>()
            locked { advertisingWaiter = waiter }
            peripheral.startAdvertising(
                config.advertiseConfig.toAppleAdvertisementData().ifEmpty { null },
            )
            awaitOperation("advertising start", waiter)
        }
        return AppleOpenResult(
            restored = restoration != null,
            advertising = peripheral.isAdvertising,
            restoredSessions = restoredSessions,
        )
    }

    override fun sendResponse(response: AppleGattResponse): Boolean {
        val target = locked {
            val pending = pendingRequests.remove(response.requestToken) ?: return false
            if (manager !== pending.peripheral && manager != pending.peripheral) return false
            pending.peripheral to pending
        }
        if (target.second.read && response.status == GattResponseStatus.Success) {
            target.second.request.value = (response.value ?: ByteArray(0)).toData()
        }
        target.first.respondToRequest(target.second.request, response.status.toAppleAttError())
        return true
    }

    override fun notify(request: AppleNotificationRequest): AppleNotificationStartResult {
        val target = locked {
            val peripheral = manager
                ?: return AppleNotificationStartResult.Disconnected
            val central = centrals[request.sessionId]
                ?: return AppleNotificationStartResult.Disconnected
            val characteristic = characteristics[request.characteristicId]
                ?: return AppleNotificationStartResult.Rejected(
                    AppleUnknownCharacteristicException(request.characteristicId),
                )
            Triple(peripheral, central, characteristic)
        }
        return try {
            if (
                target.first.updateValue(
                    value = request.value.toData(),
                    forCharacteristic = target.third,
                    onSubscribedCentrals = listOf(target.second),
                )
            ) {
                AppleNotificationStartResult.Accepted
            } else {
                AppleNotificationStartResult.Busy
            }
        } catch (cause: Throwable) {
            AppleNotificationStartResult.Rejected(cause)
        }
    }

    override fun stopAdvertising() {
        locked { manager }?.stopAdvertising()
    }

    override fun clearServices() {
        val peripheral = locked { retireManagerLocked() }
        peripheral?.delegate = null
        peripheral?.removeAllServices()
        clearHostedState()
    }

    private fun retireManagerLocked(): CBPeripheralManager? {
        callbackOwner?.retire()
        callbackOwner = null
        managerDelegate = null
        restoredState = null
        val failure = PeripheralLifecycleException("Apple peripheral manager run retired")
        serviceWaiters.values.forEach { it.completeExceptionally(failure) }
        serviceWaiters.clear()
        advertisingWaiter?.completeExceptionally(failure)
        advertisingWaiter = null
        poweredOnWaiter?.completeExceptionally(failure)
        poweredOnWaiter = null
        return manager.also { manager = null }
    }

    override fun close() {
        val peripheral = locked {
            if (closed) return
            closed = true
                    pendingRequests.clear()
            centrals.clear()
            characteristics.clear()
            serviceWaiters.values.forEach {
                it.completeExceptionally(PeripheralLifecycleException("Apple stack closed"))
            }
            serviceWaiters.clear()
            advertisingWaiter?.completeExceptionally(
                PeripheralLifecycleException("Apple stack closed"),
            )
            advertisingWaiter = null
            poweredOnWaiter?.completeExceptionally(
                PeripheralLifecycleException("Apple stack closed"),
            )
            poweredOnWaiter = null
            retireManagerLocked()
        }
        peripheral?.delegate = null
        peripheral?.stopAdvertising()
        peripheral?.removeAllServices()
    }

    private fun prepareManager(
        config: PeripheralConfig,
        listener: ApplePeripheralStackListener,
    ): CBPeripheralManager = locked {
        check(!closed) { "Apple peripheral stack is closed" }
        check(manager == null) { "Apple peripheral manager already owns a run" }
        val options = config.restorationIdentifier?.let { identifier ->
            mapOf<Any?, Any?>(CBPeripheralManagerOptionRestoreIdentifierKey to identifier)
        }
        val owner = ApplePeripheralCallbackOwner<CBPeripheralManager>(listener)
        val delegate = createDelegate(owner)
        callbackOwner = owner
        managerDelegate = delegate
        CBPeripheralManager(delegate, delegateQueue, options).also {
            owner.bind(it)
            manager = it
        }
    }

    private suspend fun awaitPoweredOn(peripheral: CBPeripheralManager) {
        if (peripheral.state == CBManagerStatePoweredOn) return
        val waiter = CompletableDeferred<Unit>()
        locked { poweredOnWaiter = waiter }
        when (peripheral.state) {
            CBManagerStatePoweredOn -> waiter.complete(Unit)
            CBManagerStateUnsupported -> waiter.completeExceptionally(
                ApplePeripheralUnavailableException("unsupported"),
            )
            CBManagerStateUnauthorized -> waiter.completeExceptionally(
                ApplePeripheralUnavailableException("unauthorized"),
            )
        }
        awaitOperation("powered-on state", waiter)
    }

    private suspend fun publishConfiguredServices(
        peripheral: CBPeripheralManager,
        services: List<GattServiceConfig>,
    ) {
        services.forEach { config ->
            val service = buildService(config)
            val waiter = CompletableDeferred<Unit>()
            locked {
                serviceWaiters[normalizeAppleUuid(config.uuid)] = waiter
            }
            peripheral.addService(service)
            awaitOperation("service ${config.uuid}", waiter)
        }
    }

    private fun buildService(config: GattServiceConfig): CBMutableService {
        val service = config.toAppleMutableService()
        @Suppress("UNCHECKED_CAST")
        val mutableCharacteristics =
            service.characteristics.orEmpty() as List<CBMutableCharacteristic>
        config.characteristics.zip(mutableCharacteristics).forEach {
                (characteristicConfig, characteristic) ->
            val id = GattCharacteristicId(characteristicConfig.uuid.toUuid())
            locked {
                check(characteristics.put(id, characteristic) == null) {
                    "Duplicate characteristic UUID ${characteristicConfig.uuid}"
                }
            }
        }
        return service
    }

    private fun adoptServices(services: List<CBMutableService>) {
        val restoredCharacteristics = mutableMapOf<GattCharacteristicId, CBMutableCharacteristic>()
        services.forEach { service ->
            service.characteristics.orEmpty().forEach { rawCharacteristic ->
                val characteristic = rawCharacteristic as? CBMutableCharacteristic ?: return@forEach
                val id = GattCharacteristicId(characteristic.UUID.UUIDString.toUuid())
                check(restoredCharacteristics.put(id, characteristic) == null) {
                    "Restored GATT database contains duplicate characteristic UUID $id"
                }
            }
        }
        locked {
            characteristics.clear()
            centrals.clear()
            pendingRequests.clear()
            characteristics.putAll(restoredCharacteristics)
        }
    }

    private fun restoredSessions(
        services: List<CBMutableService>,
    ): List<AppleRestoredSession> {
        val owner = locked { callbackOwner } ?: return emptyList()
        val sessionMaximums = mutableMapOf<PeripheralSessionId, Int>()
        val sessionSubscriptions =
            mutableMapOf<PeripheralSessionId, MutableSet<GattCharacteristicId>>()
        services.forEach { service ->
            @Suppress("UNCHECKED_CAST")
            val restoredCharacteristics =
                service.characteristics.orEmpty() as List<CBMutableCharacteristic>
            restoredCharacteristics.forEach { characteristic ->
                val characteristicId =
                    GattCharacteristicId(characteristic.UUID.UUIDString.toUuid())
                @Suppress("UNCHECKED_CAST")
                val subscribedCentrals =
                    characteristic.subscribedCentrals.orEmpty() as List<CBCentral>
                subscribedCentrals.forEach { central ->
                    if (!retainCentral(owner, central)) return emptyList()
                    val sessionId = central.sessionId()
                    sessionMaximums[sessionId] = central.maximumUpdateValueLength.toInt()
                    sessionSubscriptions.getOrPut(sessionId, ::mutableSetOf) += characteristicId
                }
            }
        }
        return sessionSubscriptions.map { (sessionId, subscriptions) ->
            AppleRestoredSession(
                sessionId = sessionId,
                maximumUpdateValueLength = sessionMaximums.getValue(sessionId),
                subscriptions = subscriptions,
            )
        }
    }

    private fun topology(services: List<GattServiceConfig>): Map<String, Set<String>> =
        services.associate { service ->
            normalizeAppleUuid(service.uuid) to service.characteristics
                .mapTo(mutableSetOf()) { normalizeAppleUuid(it.uuid) }
        }

    private fun topology(services: List<CBMutableService>): Map<String, Set<String>> =
        services.associate { service ->
            @Suppress("UNCHECKED_CAST")
            val restoredCharacteristics =
                service.characteristics.orEmpty() as List<CBCharacteristic>
            normalizeAppleUuid(service.UUID.UUIDString) to restoredCharacteristics
                .mapTo(mutableSetOf()) { normalizeAppleUuid(it.UUID.UUIDString) }
        }

    private fun resolveIds(
        owner: ApplePeripheralCallbackOwner<CBPeripheralManager>,
        characteristic: CBCharacteristic,
    ): Pair<GattServiceId, GattCharacteristicId>? {
        val service = characteristic.service ?: run {
            logger?.debug("resolveIds: characteristic ${characteristic.UUID.UUIDString} has no service, rejecting")
            return null
        }
        val characteristicId = GattCharacteristicId(characteristic.UUID.UUIDString.toUuid())
        val known = locked {
            if (callbackOwner !== owner || !owner.isActive()) return null
            characteristics[characteristicId]
        }
        if (known == null) {
            logger?.debug(
                "resolveIds: no registered characteristic for id $characteristicId " +
                    "(known ids=${locked { characteristics.keys }})",
            )
            return null
        }
        if (!belongsToAppleCharacteristicIncarnation(known, characteristic)) {
            logger?.debug(
                "resolveIds: identity mismatch for $characteristicId " +
                    "(known=$known, incoming=$characteristic) - rejecting retired attribute",
            )
            return null
        }
        return GattServiceId(service.UUID.UUIDString.toUuid()) to characteristicId
    }

    private fun retainRequest(owner: ApplePeripheralCallbackOwner<CBPeripheralManager>, peripheral: CBPeripheralManager, request: CBATTRequest, read: Boolean): AppleRequestToken? = locked {
        if (!ownsCallbackLocked(owner, peripheral)) return null
        AppleRequestToken(++nextRequestToken).also { token ->
            pendingRequests[token] = PendingRequest(peripheral, request, read)
        }
    }

    private fun rejectPendingRequest(token: AppleRequestToken) {
        val target = locked {
            val pending = pendingRequests.remove(token) ?: return
            pending.peripheral to pending.request
        }
        target.first.respondToRequest(target.second, CBATTErrorUnlikelyError)
    }

    private fun rejectRequest(peripheral: CBPeripheralManager, request: CBATTRequest, status: GattResponseStatus) {
        peripheral.respondToRequest(request, status.toAppleAttError())
    }

    private fun retainCentral(owner: ApplePeripheralCallbackOwner<CBPeripheralManager>, central: CBCentral): Boolean = locked {
        if (callbackOwner !== owner || !owner.isActive()) return false
        centrals[central.sessionId()] = central
        true
    }

    private fun ownsCallbackLocked(owner: ApplePeripheralCallbackOwner<CBPeripheralManager>, peripheral: CBPeripheralManager): Boolean =
        callbackOwner === owner && owner.listenerFor(peripheral) != null

    private fun clearHostedState() {
        locked {
            characteristics.clear()
            centrals.clear()
            pendingRequests.clear()
        }
    }

    private suspend fun awaitOperation(
        operation: String,
        waiter: CompletableDeferred<Unit>,
    ) {
        try {
            withTimeout(operationTimeout) { waiter.await() }
        } catch (cause: TimeoutCancellationException) {
            throw ApplePeripheralOperationException(operation, cause)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            throw ApplePeripheralOperationException(operation, cause)
        }
    }

    private fun CBCentral.sessionId() = PeripheralSessionId(identifier.UUIDString)

    private inline fun <T> locked(block: () -> T): T {
        stateLock.lock()
        return try {
            block()
        } finally {
            stateLock.unlock()
        }
    }

    private class PendingRequest(
        val peripheral: CBPeripheralManager,
        val request: CBATTRequest,
        val read: Boolean,
    )

    private class RestoredState(
        val services: List<CBMutableService>,
        val advertisementData: Map<Any?, Any?>?,
    )
}

internal fun normalizeAppleUuid(uuid: String): String = uuid.toUuid().toString()

private fun GattDescriptorConfig.toAppleDescriptorValue(): Any =
    when (normalizeAppleUuid(uuid)) {
        UserDescriptionDescriptorUuid -> initialValue?.decodeToString() ?: ""
        ExtendedPropertiesDescriptorUuid,
        ServerConfigurationDescriptorUuid,
        AggregateFormatDescriptorUuid,
        -> throw IllegalArgumentException(
            "Descriptor $uuid requires a typed Core Bluetooth value that byte-array config cannot represent",
        )
        else -> initialValue?.toData() ?: NSData()
    }

private const val ExtendedPropertiesDescriptorUuid =
    "00002900-0000-1000-8000-00805f9b34fb"
private const val UserDescriptionDescriptorUuid =
    "00002901-0000-1000-8000-00805f9b34fb"
private const val ServerConfigurationDescriptorUuid =
    "00002903-0000-1000-8000-00805f9b34fb"
private const val AggregateFormatDescriptorUuid =
    "00002905-0000-1000-8000-00805f9b34fb"

internal class ApplePeripheralUnavailableException(reason: String) :
    IllegalStateException("Apple BLE peripheral is $reason")

internal class AppleServicePublicationException(uuid: String, detail: String) :
    IllegalStateException("Failed to publish Apple GATT service $uuid: $detail")

internal class AppleAdvertisingException(detail: String) :
    IllegalStateException("Failed to start Apple peripheral advertising: $detail")

internal class ApplePeripheralOperationException(operation: String, cause: Throwable) :
    IllegalStateException("Apple peripheral $operation failed", cause)

internal class AppleEmptyWriteBatchException :
    IllegalStateException("Core Bluetooth delivered an empty write request batch")

internal class AppleUnknownCharacteristicException(characteristicId: GattCharacteristicId) :
    IllegalArgumentException("Unknown Apple GATT characteristic $characteristicId")

// UUID equality identifies an attribute name, not the native service incarnation.
internal fun belongsToAppleCharacteristicIncarnation(
    owned: CBCharacteristic,
    incoming: CBCharacteristic,
): Boolean = owned === incoming || owned == incoming

/** A delegate holds this immutable run/listener owner, never the stack's next listener. */
internal class ApplePeripheralCallbackOwner<T : Any>(private val listener: ApplePeripheralStackListener) {
    private val active = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val native = kotlinx.coroutines.flow.MutableStateFlow<T?>(null)
    fun bind(target: T) {
        check(native.compareAndSet(null, target)) { "Native peripheral callback owner is already bound" }
    }
    fun isActive(): Boolean = active.value
    fun listenerFor(target: T): ApplePeripheralStackListener? =
        listener.takeIf { active.value && (native.value === target || native.value == target) }
    fun retire() { active.value = false }
}

package dev.bluefalcon

import com.monkopedia.sdbus.InterfaceName
import com.monkopedia.sdbus.ObjectPath
import com.monkopedia.sdbus.PropertyName
import com.monkopedia.sdbus.ServiceName
import com.monkopedia.sdbus.Variant
import com.monkopedia.sdbus.createObject
import com.monkopedia.sdbus.createProxy
import com.monkopedia.sdbus.createSystemBusConnection
import dev.bluefalcon.bluez.*
import com.monkopedia.sdbus.ObjectManagerProxy as SdbusObjectManagerProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.uuid.Uuid

actual class BlueFalcon actual constructor(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) {
    actual val scope = CoroutineScope(
        context.scope.coroutineContext + SupervisorJob(context.scope.coroutineContext[Job])
    )
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    actual var isScanning: Boolean = false

    internal actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)
    actual val managerState: StateFlow<BluetoothManagerState> =
        MutableStateFlow(BluetoothManagerState.Ready)

    private val connection = createSystemBusConnection()
    private val bluezService = ServiceName("org.bluez")
    private val adapterPath = ObjectPath("/org/bluez/hci0")
    private lateinit var adapterProxy: Adapter1Proxy
    private lateinit var objectManagerProxy: SdbusObjectManagerProxy

    private val knownPeripherals = mutableMapOf<ObjectPath, BluetoothPeripheralImpl>()
    private val connectedDevices = mutableMapOf<ObjectPath, ConnectedDevice>()
    private var scanJob: Job? = null

    /** Holds the proxy and observation scope for a connected device. */
    private class ConnectedDevice(
        val proxy: Device1Proxy,
        val observationScope: Job,
    )

    private val initJob = scope.launch {
        // The D-Bus event loop must be shut down before a new connection
        // can safely be used. BlueZ's disconnect is synchronous at the D-Bus
        // level but leaveEventLoop() is async, so we wait here to ensure the
        // previous instance's event loop has fully stopped.
        pendingShutdown?.join()
        pendingShutdown = null

        adapterProxy = Adapter1Proxy(
            createProxy(connection, bluezService, adapterPath)
        )
        objectManagerProxy = SdbusObjectManagerProxy(
            createProxy(connection, bluezService, ObjectPath("/"))
        )
        connection.enterEventLoopAsync()

        // Agent registration is fire-and-forget — pairing will work once
        // the agent is registered, but other operations don't depend on it.
        registerAgent()
    }

    /**
     * Registers a NoInputNoOutput pairing agent with BlueZ.
     *
     * blue-falcon's API only exposes createBond/removeBond with no passkey
     * or PIN callbacks, so NoInputNoOutput (Just Works) is the only
     * pairing mode we can support.
     */
    private fun registerAgent() {
        val agentPath = ObjectPath("/dev/bluefalcon/agent")
        val agent = NoInputNoOutputAgent(createObject(connection, agentPath))
        agent.register()
        val agentManager = AgentManager1Proxy(
            createProxy(connection, bluezService, ObjectPath("/org/bluez"))
        )
        scope.launch {
            try {
                agentManager.registerAgent(agentPath, "NoInputNoOutput")
                agentManager.requestDefaultAgent(agentPath)
                log?.info("Registered NoInputNoOutput pairing agent")
            } catch (e: Exception) {
                log?.error("Failed to register pairing agent: ${e.message}", e)
            }
        }
    }

    // ---- Scanning ----

    actual fun scan(filters: List<ServiceFilter>) {
        log?.info("Scan started with filters: $filters")
        isScanning = true

        scanJob = scope.launch {
            initJob.join()
            try {
                configureDiscoveryFilter(filters)
                startDiscovery()

                val deviceInterface = InterfaceName("org.bluez.Device1")
                objectManagerProxy.objectsFor(deviceInterface).collectLatest { paths ->
                    coroutineScope {
                        for (path in paths) {
                            if (!path.value.startsWith(adapterPath.value + "/dev_")) continue
                            launch {
                                objectManagerProxy.objectData(path).collect { data ->
                                    val devProps = data[deviceInterface] ?: return@collect
                                    handleDeviceFound(path, devProps)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log?.error("Scan failed: ${e.message}", e)
                isScanning = false
            }
        }
    }

    actual fun stopScanning() {
        log?.info("Scan stopped")
        isScanning = false
        scope.launch {
            try { adapterProxy.stopDiscovery() } catch (_: Exception) {}
            scanJob?.cancel()
            scanJob = null
        }
    }

    actual fun clearPeripherals() {
        _peripherals.value = emptySet()
    }

    // ---- Connection ----

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        log?.info("Connecting to ${impl.uuid}")

        scope.launch {
            initJob.join()
            try {
                // Cancel any existing connection to this device
                connectedDevices.remove(impl.device.objectPath)?.observationScope?.cancel()

                val deviceProxy = Device1Proxy(
                    createProxy(connection, bluezService, impl.device.objectPath)
                )
                val observationScope = observeDeviceProperties(impl, deviceProxy)
                connectedDevices[impl.device.objectPath] = ConnectedDevice(deviceProxy, observationScope)
                deviceProxy.connect()
            } catch (e: Exception) {
                log?.error("Connect failed: ${e.message}", e)
            }
        }
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            // Use NonCancellable so scope.cancel() in destroy() doesn't
            // kill the D-Bus disconnect call mid-flight
            withContext(NonCancellable) {
                try {
                    val device = connectedDevices.remove(impl.device.objectPath)
                    device?.observationScope?.cancel()
                    device?.proxy?.disconnect()
                } catch (e: Exception) {
                    log?.error("Disconnect failed: ${e.message}", e)
                }
            }
            // Always notify, matching Android behavior
            notifyDelegates { it.didDisconnect(bluetoothPeripheral) }
        }
    }

    actual fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        val devPath = ObjectPath(
            "${adapterPath.value}/dev_${identifier.replace(":", "_")}"
        )
        return knownPeripherals[devPath]
    }

    actual fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        val device = connectedDevices[impl.device.objectPath]
        return try {
            if (device?.proxy?.connected == true) BluetoothPeripheralState.Connected
            else BluetoothPeripheralState.Disconnected
        } catch (_: Exception) {
            BluetoothPeripheralState.Unknown
        }
    }

    actual fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) {
        // No BlueZ equivalent — connection parameters are managed by the kernel.
    }

    // ---- Service / Characteristic Discovery ----

    actual fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            resolveGattObjects(impl)
            notifyDelegates { it.didDiscoverServices(bluetoothPeripheral) }
        }
    }

    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        notifyDelegates { it.didDiscoverCharacteristics(bluetoothPeripheral) }
    }

    // ---- Read / Write ----

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        scope.launch {
            try {
                val charProxy = createCharProxy(bluetoothCharacteristic)
                val value = charProxy.readValue(emptyMap())
                bluetoothCharacteristic._value = value.toUByteArray().asByteArray()
                notifyDelegates {
                    it.didCharacteristcValueChanged(bluetoothPeripheral, bluetoothCharacteristic)
                }
            } catch (e: Exception) {
                log?.error("Read characteristic failed: ${e.message}", e)
            }
        }
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristicWithoutEncoding(
            bluetoothPeripheral, bluetoothCharacteristic,
            value.encodeToByteArray(), writeType
        )
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        writeCharacteristicWithoutEncoding(
            bluetoothPeripheral, bluetoothCharacteristic, value, writeType
        )
    }

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        scope.launch {
            try {
                val charProxy = createCharProxy(bluetoothCharacteristic)
                val options = mutableMapOf<String, Variant>()
                if (writeType == 1) {
                    options["type"] = Variant("command")
                } else {
                    options["type"] = Variant("request")
                }
                charProxy.writeValue(value.asUByteArray().toList(), options)
                bluetoothCharacteristic._value = value
                notifyDelegates {
                    it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, true)
                }
            } catch (e: Exception) {
                log?.error("Write failed: ${e.message}", e)
                notifyDelegates {
                    it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, false)
                }
            }
        }
    }

    // ---- Notify / Indicate ----

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        scope.launch {
            try {
                val charProxy = createCharProxy(bluetoothCharacteristic)
                if (notify && !bluetoothCharacteristic.isNotifying) {
                    val job = scope.launch {
                        charProxy.valueProperty.changes().collect { value ->
                            bluetoothCharacteristic._value = value.toUByteArray().asByteArray()
                            notifyDelegates {
                                it.didCharacteristcValueChanged(
                                    bluetoothPeripheral, bluetoothCharacteristic
                                )
                            }
                        }
                    }
                    bluetoothCharacteristic._notifyJob = job
                    charProxy.startNotify()
                    bluetoothCharacteristic._isNotifying = true
                } else if (!notify && bluetoothCharacteristic.isNotifying) {
                    charProxy.stopNotify()
                    bluetoothCharacteristic._isNotifying = false
                    bluetoothCharacteristic._notifyJob?.cancel()
                    bluetoothCharacteristic._notifyJob = null
                }
                notifyDelegates {
                    it.didUpdateNotificationStateFor(bluetoothPeripheral, bluetoothCharacteristic)
                }
            } catch (e: Exception) {
                log?.error("Notify failed: ${e.message}", e)
            }
        }
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // BlueZ StartNotify handles both notify and indicate
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, indicate)
    }

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)
    }

    // ---- Descriptors ----

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        scope.launch {
            try {
                val descProxy = GattDescriptor1Proxy(
                    createProxy(connection, bluezService, bluetoothCharacteristicDescriptor.objectPath)
                )
                val value = descProxy.readValue(emptyMap())
                bluetoothCharacteristicDescriptor._value = value.toUByteArray().asByteArray()
                notifyDelegates {
                    it.didReadDescriptor(bluetoothPeripheral, bluetoothCharacteristicDescriptor)
                }
            } catch (e: Exception) {
                log?.error("Read descriptor failed: ${e.message}", e)
            }
        }
    }

    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        scope.launch {
            try {
                val descProxy = GattDescriptor1Proxy(
                    createProxy(connection, bluezService, bluetoothCharacteristicDescriptor.objectPath)
                )
                descProxy.writeValue(value.asUByteArray().toList(), emptyMap())
                bluetoothCharacteristicDescriptor._value = value
                notifyDelegates {
                    it.didWriteDescriptor(bluetoothPeripheral, bluetoothCharacteristicDescriptor)
                }
            } catch (e: Exception) {
                log?.error("Write descriptor failed: ${e.message}", e)
            }
        }
    }

    // ---- MTU ----

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            try {
                val firstChar = impl.services.values.flatMap { it.characteristics }.firstOrNull()
                if (firstChar != null) {
                    val charProxy = createCharProxy(firstChar)
                    impl.mtuSize = charProxy.mTU.toInt()
                }
                notifyDelegates { it.didUpdateMTU(bluetoothPeripheral, 0) }
            } catch (e: Exception) {
                log?.error("changeMTU failed: ${e.message}", e)
                notifyDelegates { it.didUpdateMTU(bluetoothPeripheral, -1) }
            }
        }
    }

    // ---- L2CAP ----

    actual fun openL2capChannel(bluetoothPeripheral: BluetoothPeripheral, psm: Int) {
        // L2CAP CoC is not exposed via BlueZ's D-Bus API. It would require
        // raw Linux sockets (AF_BLUETOOTH, BTPROTO_L2CAP) outside of sdbus-kotlin.
        notifyDelegates { it.didOpenL2capChannel(bluetoothPeripheral, null) }
    }

    // ---- Bonding ----

    actual fun createBond(bluetoothPeripheral: BluetoothPeripheral) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            try {
                val device = connectedDevices[impl.device.objectPath]
                    ?: throw IllegalStateException("Not connected")
                device.proxy.pair()
                // didBondStateChanged fires from the connectedProperty
                // observer when the Paired property becomes true
            } catch (e: Exception) {
                log?.error("Pair failed: ${e.message}", e)
                notifyDelegates {
                    it.didBondStateChanged(bluetoothPeripheral, BlueFalconBondState.None)
                }
            }
        }
    }

    actual fun removeBond(bluetoothPeripheral: BluetoothPeripheral) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            try {
                adapterProxy.removeDevice(impl.device.objectPath)
                notifyDelegates {
                    it.didBondStateChanged(bluetoothPeripheral, BlueFalconBondState.None)
                }
            } catch (e: Exception) {
                log?.error("Remove bond failed: ${e.message}", e)
            }
        }
    }

    // ---- Lifecycle ----

    actual fun destroy() {
        isScanning = false
        scanJob?.cancel()
        scanJob = null
        connectedDevices.values.forEach { it.observationScope.cancel() }
        connectedDevices.clear()
        // The D-Bus event loop runs on its own thread (started by
        // enterEventLoopAsync). leaveEventLoop() is suspend, so we kick it
        // off asynchronously. The next BlueFalcon instance awaits this in
        // its initJob to ensure the old event loop is fully stopped before
        // opening a new D-Bus connection.
        pendingShutdown = CoroutineScope(Dispatchers.IO).launch {
            connection.leaveEventLoop()
        }
        scope.cancel()
    }

    // ---- Private helpers ----

    private fun notifyDelegates(action: (BlueFalconDelegate) -> Unit) {
        delegates.toList().forEach(action)
    }

    private fun createCharProxy(char: BluetoothCharacteristic) = GattCharacteristic1Proxy(
        createProxy(connection, bluezService, char.objectPath)
    )

    private suspend fun configureDiscoveryFilter(filters: List<ServiceFilter>) {
        val filterMap = mutableMapOf<String, Variant>(
            "Transport" to Variant("le"),
            "DuplicateData" to Variant(false),
        )
        if (filters.isNotEmpty()) {
            val uuids = filters.flatMap { it.serviceUuids }.map { it.toString() }
            if (uuids.isNotEmpty()) {
                filterMap["UUIDs"] = Variant(uuids)
            }
        }
        try {
            adapterProxy.setDiscoveryFilter(filterMap)
        } catch (e: Exception) {
            log?.debug("setDiscoveryFilter failed (may already be set): ${e.message}")
        }
    }

    private suspend fun startDiscovery() {
        try {
            adapterProxy.startDiscovery()
        } catch (e: Exception) {
            log?.debug("startDiscovery failed (may already be discovering): ${e.message}")
        }
    }

    /** Reads device properties from the ObjectManager's cached state rather than
     *  creating a Device1Proxy per device, avoiding D-Bus round-trips during scan. */
    private fun handleDeviceFound(path: ObjectPath, properties: Map<PropertyName, Variant>) {
        if (!path.value.startsWith(adapterPath.value)) return

        val device = NativeBluetoothDevice(path)
        val peripheral = knownPeripherals.getOrPut(path) { BluetoothPeripheralImpl(device) }

        properties[PropertyName("Name")]?.let { peripheral._name = it.get<String>() }
        properties[PropertyName("RSSI")]?.let { peripheral.rssi = it.get<Short>().toFloat() }

        _peripherals.tryEmit(_peripherals.value + setOf(peripheral))

        val advData = mutableMapOf<AdvertisementDataRetrievalKeys, Any>()
        properties[PropertyName("Name")]?.let { advData[AdvertisementDataRetrievalKeys.LocalName] = it.get<String>() }
        advData[AdvertisementDataRetrievalKeys.IsConnectable] = 1
        notifyDelegates { it.didDiscoverDevice(peripheral, advData) }
    }

    /** Launches coroutines that observe a connected device's property changes. */
    private fun observeDeviceProperties(
        impl: BluetoothPeripheralImpl,
        deviceProxy: Device1Proxy
    ): Job = scope.launch {
        launch {
            deviceProxy.connectedProperty.changes().collect { connected ->
                if (connected) {
                    notifyDelegates { it.didConnect(impl) }
                    if (autoDiscoverAllServicesAndCharacteristics) {
                        launch { awaitAndResolveServices(impl, deviceProxy) }
                    }
                } else {
                    notifyDelegates { it.didDisconnect(impl) }
                }
            }
        }
        launch {
            deviceProxy.servicesResolvedProperty.changes().collect { resolved ->
                if (resolved) {
                    resolveGattObjects(impl)
                    notifyDelegates { it.didDiscoverServices(impl) }
                }
            }
        }
        launch {
            deviceProxy.pairedProperty.changes().collect { paired ->
                notifyDelegates {
                    it.didBondStateChanged(
                        impl,
                        if (paired) BlueFalconBondState.Bonded else BlueFalconBondState.None
                    )
                }
            }
        }
        launch {
            deviceProxy.rSSIProperty.changesOrNull().collect { rssi ->
                rssi?.let {
                    impl.rssi = it.toFloat()
                    notifyDelegates { d -> d.didRssiUpdate(impl) }
                }
            }
        }
    }

    private suspend fun awaitAndResolveServices(
        peripheral: BluetoothPeripheralImpl,
        deviceProxy: Device1Proxy
    ) {
        try {
            if (deviceProxy.servicesResolved) {
                resolveGattObjects(peripheral)
                notifyDelegates { it.didDiscoverServices(peripheral) }
            }
        } catch (e: Exception) {
            log?.error("awaitAndResolveServices failed: ${e.message}", e)
        }
    }

    private fun resolveGattObjects(peripheral: BluetoothPeripheralImpl) {
        try {
            val managed = objectManagerProxy.getManagedObjects()
            val devPrefix = peripheral.device.objectPath.value

            val svcInterface = InterfaceName("org.bluez.GattService1")
            val charInterface = InterfaceName("org.bluez.GattCharacteristic1")
            val descInterface = InterfaceName("org.bluez.GattDescriptor1")

            val services = mutableListOf<BluetoothService>()
            val characteristics = mutableMapOf<ObjectPath, BluetoothCharacteristic>()

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val svcProps = interfaces[svcInterface] ?: continue
                val uuidStr = svcProps[PropertyName("UUID")]?.get<String>() ?: continue
                services.add(BluetoothService(path, Uuid.parse(uuidStr)))
            }

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val charProps = interfaces[charInterface] ?: continue
                val uuidStr = charProps[PropertyName("UUID")]?.get<String>() ?: continue
                val svcPath = charProps[PropertyName("Service")]?.get<ObjectPath>() ?: continue
                val char = BluetoothCharacteristic(path, Uuid.parse(uuidStr), svcPath)
                val parent = services.find { it.objectPath == svcPath }
                if (parent != null) {
                    parent.addCharacteristic(char)
                    char.setService(parent)
                }
                characteristics[path] = char
            }

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val descProps = interfaces[descInterface] ?: continue
                val uuidStr = descProps[PropertyName("UUID")]?.get<String>() ?: continue
                val charPath = descProps[PropertyName("Characteristic")]?.get<ObjectPath>() ?: continue
                val desc = BluetoothCharacteristicDescriptor(path, Uuid.parse(uuidStr), charPath)
                characteristics[charPath]?.addDescriptor(desc)
            }

            peripheral._servicesFlow.tryEmit(services)
        } catch (e: Exception) {
            log?.error("resolveGattObjects failed: ${e.message}", e)
        }
    }

    companion object {
        private var pendingShutdown: Job? = null
    }
}

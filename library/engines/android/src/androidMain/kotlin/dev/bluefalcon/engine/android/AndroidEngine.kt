package dev.bluefalcon.engine.android

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android implementation of BlueFalconEngine using Android BLE APIs.
 * Provides full BLE support including bonding, L2CAP, connection priority, and GATT operations.
 */
class AndroidEngine(
    private val context: Context,
    private val logger: Logger? = null,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean = true
) : BlueFalconEngine {
    
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.NotReady)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications
    
    override var isScanning: Boolean = false
        private set
    
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    
    private val scanCallback = BluetoothScanCallBack()
    private val gattCallback = GattClientCallback()
    
    var transportMethod: Int = BluetoothDevice.TRANSPORT_LE
    
    private var isBondReceiverRegistered = false
    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                
                logger?.debug("Bond state changed for ${device.address}")
            }
        }
    }
    
    init {
        BluetoothStateMonitor.register(context, this)
        _managerState.value = try {
            if (bluetoothManager.adapter?.isEnabled == true) BluetoothManagerState.Ready
            else BluetoothManagerState.NotReady
        } catch (_: SecurityException) {
            BluetoothManagerState.NotReady
        }
        logger?.info("AndroidEngine initialized")
    }
    
    internal fun onAdapterStateChanged(adapterOn: Boolean) {
        _managerState.value = if (adapterOn) {
            BluetoothManagerState.Ready
        } else {
            gattCallback.disconnectAllOnAdapterOff()
            BluetoothManagerState.NotReady
        }
    }
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        logger?.info("Starting scan with ${filters.size} filters")
        isScanning = true
        
        val scanFilters: List<ScanFilter> = if (filters.isEmpty()) {
            listOf(ScanFilter.Builder().build())
        } else {
            filters.map { filter ->
                val filterBuilder = ScanFilter.Builder()
                val parcelUuid = android.os.ParcelUuid(java.util.UUID.fromString(filter.uuid.toString()))
                filterBuilder.setServiceUuid(parcelUuid)
                filterBuilder.build()
            }
        }
        
        val settings = ScanSettings.Builder().build()
        bluetoothManager.adapter?.bluetoothLeScanner?.startScan(scanFilters, settings, scanCallback)
    }
    
    override suspend fun stopScanning() {
        logger?.info("Stopping scan")
        isScanning = false
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        logger?.debug("Connecting to ${peripheral.name ?: peripheral.uuid}")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        device.connectGatt(context, autoConnect, gattCallback, transportMethod)
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        logger?.debug("Disconnecting from ${peripheral.name ?: peripheral.uuid}")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gatt.disconnect()
            gattCallback.scheduleDisconnectTimeout(gatt)
        }
    }
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return BluetoothPeripheralState.Unknown
        return when (bluetoothManager.getConnectionState(device, BluetoothProfile.GATT)) {
            BluetoothProfile.STATE_CONNECTED -> BluetoothPeripheralState.Connected
            BluetoothProfile.STATE_CONNECTING -> BluetoothPeripheralState.Connecting
            BluetoothProfile.STATE_DISCONNECTED -> BluetoothPeripheralState.Disconnected
            BluetoothProfile.STATE_DISCONNECTING -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }
    }
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return runCatching {
            bluetoothManager.adapter
                ?.getRemoteDevice(identifier)
                ?.let { AndroidBluetoothPeripheral(it) }
        }.onFailure { e ->
            logger?.error("retrievePeripheral error: ${e.message}")
        }.getOrNull()
    }
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        logger?.debug("requestConnectionPriority: $priority")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gatt.requestConnectionPriority(priority.toNative())
        }
    }
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { it.discoverServices() }
    }
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidPeripheral = peripheral as AndroidBluetoothPeripheral
        if (!androidPeripheral.services.any { it.uuid == service.uuid }) {
            gattCallback.gattsForDevice(device).forEach { it.discoverServices() }
        }
    }
    
    override suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidChar = (characteristic as? AndroidBluetoothCharacteristic)?.characteristic ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            fetchCharacteristic(androidChar, gatt).forEach { gatt.readCharacteristic(it) }
        }
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }
    
    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        logger?.debug("Writing value {length = ${value.size}, bytes = 0x${value.toHexString()}} with response $writeType")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidChar = (characteristic as? AndroidBluetoothCharacteristic)?.characteristic ?: return
        
        gattCallback.gattsForDevice(device).forEach { gatt ->
            fetchCharacteristic(androidChar, gatt).forEach { char ->
                writeType?.let { char.writeType = it }
                char.setValue(value)
                gatt.writeCharacteristic(char)
            }
        }
    }
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        setCharacteristicNotification(
            peripheral,
            characteristic,
            notify,
            if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        setCharacteristicNotification(
            peripheral,
            characteristic,
            indicate,
            if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )
    }
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidDesc = (descriptor as? AndroidBluetoothCharacteristicDescriptor)?.descriptor ?: return
        gattCallback.gattsForDevice(device).forEach { it.readDescriptor(androidDesc) }
        logger?.debug("readDescriptor -> ${descriptor.uuid}")
    }
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        logger?.debug("writeDescriptor ${descriptor.uuid} value: $value")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidDesc = (descriptor as? AndroidBluetoothCharacteristicDescriptor)?.descriptor ?: return
        
        gattCallback.gattsForDevice(device).forEach { gatt ->
            androidDesc.value = value
            gatt.writeDescriptor(androidDesc)
        }
    }
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        logger?.debug("changeMTU -> ${peripheral.uuid} mtuSize: $mtuSize")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { it.requestMtu(mtuSize) }
    }
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        logger?.debug("refreshGattCache for ${peripheral.uuid}")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return false
        var result = false
        gattCallback.gattsForDevice(device).forEach { gatt ->
            try {
                val refreshMethod = gatt.javaClass.getMethod("refresh")
                val refreshed = refreshMethod.invoke(gatt) as Boolean
                logger?.debug("GATT cache refresh: $refreshed")
                result = result || refreshed
            } catch (e: Exception) {
                logger?.error("Failed to refresh GATT cache: ${e.message}", e)
            }
        }
        return result
    }
    
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            logger?.error("L2Cap channels require Android 10 (API 29) or higher")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val socket = device.createL2capChannel(psm)
                socket.connect()
                logger?.info("L2CAP channel opened on PSM $psm")
            } catch (e: Exception) {
                logger?.error("Failed to open L2Cap channel: ${e.message}")
            }
        }
    }
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        logger?.debug("createBond ${peripheral.uuid}")
        ensureBondReceiverRegistered()
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        device.createBond()
    }
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        logger?.debug("removeBond ${peripheral.uuid}")
        ensureBondReceiverRegistered()
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        try {
            device::class.java.getMethod("removeBond").invoke(device)
        } catch (e: NoSuchMethodException) {
            logger?.error("removeBond method not available on this device: ${e.message}")
        } catch (e: Exception) {
            logger?.error("Failed to remove bond: ${e.message}")
        }
    }
    
    private fun ensureBondReceiverRegistered() {
        if (!isBondReceiverRegistered) {
            context.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            isBondReceiverRegistered = true
        }
    }
    
    private fun setCharacteristicNotification(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enable: Boolean,
        descriptorValue: ByteArray?
    ) {
        logger?.debug("setCharacteristicNotification for ${characteristic.uuid} enable: $enable")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidChar = (characteristic as? AndroidBluetoothCharacteristic)?.characteristic ?: return
        
        gattCallback.gattsForDevice(device).forEach { gatt ->
            fetchCharacteristic(androidChar, gatt).forEach { char ->
                gatt.setCharacteristicNotification(char, enable)
                descriptorValue?.let { value ->
                    char.descriptors.forEach { descriptor ->
                        descriptor.value = value
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }
    }
    
    private fun fetchCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        gatt: BluetoothGatt
    ): List<BluetoothGattCharacteristic> =
        gatt.services
            .flatMap { service ->
                service.characteristics.filter { it.uuid == characteristic.uuid }
            }
    
    fun destroy() {
        if (isBondReceiverRegistered) {
            context.unregisterReceiver(bondStateReceiver)
            isBondReceiverRegistered = false
        }
        BluetoothStateMonitor.unregister(context, this)
    }
    
    // Scan callback implementation
    private inner class BluetoothScanCallBack : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            addScanResult(result)
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { addScanResult(it) }
        }
        
        override fun onScanFailed(errorCode: Int) {
            logger?.error("Failed to scan with code $errorCode")
        }
        
        private fun addScanResult(result: ScanResult?) {
            logger?.debug("addScanResult $result")
            result?.device?.let { device ->
                val bluetoothPeripheral = AndroidBluetoothPeripheral(device)
                bluetoothPeripheral.rssi = result.rssi.toFloat()
                _peripherals.value = _peripherals.value + setOf(bluetoothPeripheral)
            }
        }
    }
    
    // GATT callback implementation
    private inner class GattClientCallback : BluetoothGattCallback() {
        internal val gatts: MutableList<BluetoothGatt> = CopyOnWriteArrayList()
        private val disconnectHandler = Handler(Looper.getMainLooper())
        private val pendingTimeouts = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
        
        private fun addGatt(gatt: BluetoothGatt) {
            if (gatts.firstOrNull { it.device.address == gatt.device.address } == null) {
                gatts.add(gatt)
            }
        }
        
        fun removeGatt(gatt: BluetoothGatt) {
            gatts.remove(gatt)
        }
        
        fun gattsForDevice(device: BluetoothDevice): List<BluetoothGatt> =
            gatts.filter { it.device.address == device.address }
        
        fun scheduleDisconnectTimeout(gatt: BluetoothGatt) {
            val address = gatt.device.address
            cancelDisconnectTimeout(address)
            val timeoutRunnable = Runnable {
                pendingTimeouts.remove(address)
                if (gatts.contains(gatt)) {
                    logger?.warn("Disconnect timeout for $address — forcing close")
                    gatts.remove(gatt)
                    gatt.close()
                }
            }
            pendingTimeouts[address] = timeoutRunnable
            disconnectHandler.postDelayed(timeoutRunnable, DISCONNECT_TIMEOUT_MS)
        }
        
        private fun cancelDisconnectTimeout(address: String) {
            pendingTimeouts.remove(address)?.let { disconnectHandler.removeCallbacks(it) }
        }
        
        fun disconnectAllOnAdapterOff() {
            pendingTimeouts.keys.toList().forEach { cancelDisconnectTimeout(it) }
            val connectedGatts = gatts.toList()
            gatts.clear()
            connectedGatts.forEach { gatt ->
                logger?.info("Adapter off - forcing disconnect for ${gatt.device.address}")
                gatt.close()
            }
        }
        
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            logger?.debug("onConnectionStateChange status: $status newState: $newState")
            gatt?.device?.let { device ->
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    logger?.info("Connected to ${device.address}")
                    addGatt(gatt)
                    if (autoDiscoverAllServicesAndCharacteristics) {
                        gatt.readRemoteRssi()
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    logger?.info("Disconnected from ${device.address}")
                    cancelDisconnectTimeout(device.address)
                    gatts.remove(gatt)
                    gatt.close()
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            logger?.info("onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger?.error("Service discovery failed with status $status")
                return
            }
            gatt?.device?.let { device ->
                gatt.services.let { services ->
                    val peripheral = _peripherals.value.find { (it as? AndroidBluetoothPeripheral)?.device?.address == device.address }
                    (peripheral as? AndroidBluetoothPeripheral)?._servicesFlow?.value =
                        services.map { AndroidBluetoothService(it) }
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            logger?.debug("onMtuChanged mtu=$mtu status=$status")
            gatt?.device?.let { device ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val peripheral = _peripherals.value.find { (it as? AndroidBluetoothPeripheral)?.device?.address == device.address }
                    (peripheral as? AndroidBluetoothPeripheral)?.mtuSize = mtu
                }
            }
        }
        
        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            logger?.debug("onReadRemoteRssi $rssi")
            gatt?.device?.let { device ->
                val peripheral = _peripherals.value.find { (it as? AndroidBluetoothPeripheral)?.device?.address == device.address }
                (peripheral as? AndroidBluetoothPeripheral)?.rssi = rssi.toFloat()
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            logger?.debug("onCharacteristicRead ${characteristic?.uuid} status=$status")
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            logger?.debug("onCharacteristicChanged ${characteristic?.uuid}")
            if (gatt == null || characteristic == null) return

            val value = characteristic.value?.copyOf() ?: return
            val peripheral =
                _peripherals.value.find { (it as? AndroidBluetoothPeripheral)?.device?.address == gatt.device.address }
                    ?: AndroidBluetoothPeripheral(gatt.device)
            val bluetoothCharacteristic = AndroidBluetoothCharacteristic(characteristic)
            bluetoothCharacteristic.emitNotification(value)
            _characteristicNotifications.tryEmit(
                CharacteristicNotification(
                    peripheral = peripheral,
                    characteristic = bluetoothCharacteristic,
                    value = value
                )
            )
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            logger?.debug("onCharacteristicWrite ${characteristic?.uuid} status=$status")
        }
        
        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            logger?.debug("onDescriptorRead ${descriptor?.uuid}")
        }
        
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            logger?.debug("onDescriptorWrite ${descriptor?.uuid} status=$status")
        }
    }
    
    companion object {
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
    }
}

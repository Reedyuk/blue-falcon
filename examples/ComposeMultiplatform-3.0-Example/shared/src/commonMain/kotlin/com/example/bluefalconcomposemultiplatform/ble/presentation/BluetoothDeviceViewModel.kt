package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothAdvertiser
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.plugins.broadcast.DeviceBroadcastPlugin
import dev.bluefalcon.plugins.clone.CloneConfig
import dev.bluefalcon.plugins.clone.DeviceClonePlugin
import dev.bluefalcon.plugins.nordicfota.FotaState
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class BluetoothDeviceViewModel(
    private val blueFalcon: BlueFalcon,
    private val fotaPlugin: NordicFotaPlugin,
    private val advertiser: BluetoothAdvertiser
): ViewModel() {

    private val clonePlugin = DeviceClonePlugin(CloneConfig().apply {
        readCharacteristicValues = true
        readDescriptorValues = true
        platform = "ComposeMultiplatform"
    })

    private val broadcastPlugin = DeviceBroadcastPlugin()

    private val _deviceState: MutableStateFlow<BluetoothDeviceState> = MutableStateFlow(BluetoothDeviceState())
    val deviceState: StateFlow<BluetoothDeviceState> get() = _deviceState

    init {
        // Collect peripherals from BlueFalcon's StateFlow
        CoroutineScope(Dispatchers.IO).launch {
            blueFalcon.peripherals.collect { peripherals ->
                _deviceState.update { currentState ->
                    currentState.copy(
                        devices = buildDevices(
                            peripherals = peripherals,
                            previousDevices = currentState.devices
                        )
                    )
                }
            }
        }

        // Collect characteristic notifications and update the UI state
        CoroutineScope(Dispatchers.IO).launch {
            blueFalcon.engine.characteristicNotifications.collect { notification ->
                val peripheralId = notification.peripheral.uuid
                val charUuid = notification.characteristic.uuid.toString()
                val hex = notification.value.joinToString(" ") { byte ->
                    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                }

                _deviceState.update { state ->
                    val updatedDevices = state.devices.toMutableMap()
                    updatedDevices[peripheralId]?.let { device ->
                        val updatedNotifications = device.notificationData.toMutableMap()
                        updatedNotifications[charUuid] = hex
                        updatedDevices[peripheralId] = device.copy(
                            notificationData = updatedNotifications,
                            updateCount = device.updateCount + 1
                        )
                    }
                    state.copy(devices = HashMap(updatedDevices))
                }
            }
        }

        // Collect RSSI updates so the scan list stays current without a full peripherals re-emission
        CoroutineScope(Dispatchers.IO).launch {
            blueFalcon.rssiUpdates.collect { (uuid, rssi) ->
                _deviceState.update { state ->
                    val updatedDevices = state.devices.toMutableMap()
                    updatedDevices[uuid]?.let { device ->
                        updatedDevices[uuid] = device.copy(rssi = rssi)
                    }
                    state.copy(devices = HashMap(updatedDevices))
                }
            }
        }

        // Collect FOTA state changes and update the relevant device
        CoroutineScope(Dispatchers.IO).launch {
            fotaPlugin.state.collect { fotaState ->
                _deviceState.update { currentState ->
                    val selectedId = currentState.selectedDeviceId ?: return@update currentState
                    val device = currentState.devices[selectedId] ?: return@update currentState
                    val updatedDevices = currentState.devices.toMutableMap()
                    updatedDevices[selectedId] = device.copy(fotaState = fotaState)
                    currentState.copy(devices = HashMap(updatedDevices))
                }
            }
        }

        // Mirror broadcast plugin state into device state
        CoroutineScope(Dispatchers.IO).launch {
            broadcastPlugin.broadcastState.collect { broadcastState ->
                _deviceState.update { it.copy(broadcastState = broadcastState) }
            }
        }
    }

    fun onEvent(event: UiEvent) {
        when(event) {
            UiEvent.OnScanClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching { blueFalcon.stopScanning() }
                            .onFailure { println("Failed to stop existing scan: ${it.message}") }
                        blueFalcon.clearPeripherals()
                        _deviceState.update {
                            it.copy(
                                devices = hashMapOf(),
                                isScanning = true
                            )
                        }
                        val currentFilter = _deviceState.value.scanUuidFilter.trim()
                        if (currentFilter.isNotBlank()) {
                            try {
                                val serviceFilter = ServiceFilter(
                                    currentFilter.toUuid()
                                )
                                blueFalcon.scan(filters = listOf(serviceFilter))
                            } catch (e: Exception) {
                                // If UUID parsing fails, scan without filters
                                println("Invalid UUID filter, scanning without filters: ${e.message}")
                                blueFalcon.scan()
                            }
                        } else {
                            blueFalcon.scan()
                        }
                    } catch (e: Exception) {
                        println("Failed to start scan: ${e.message}")
                    }
                }
            }

            UiEvent.OnStopScanClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        blueFalcon.stopScanning()
                        _deviceState.update { it.copy(isScanning = false) }
                    } catch (e: Exception) {
                        println("Failed to stop scan: ${e.message}")
                    }
                }
            }

            is UiEvent.OnScanUuidFilterChanged -> {
                _deviceState.update { state ->
                    state.copy(scanUuidFilter = event.value)
                }
            }

            is UiEvent.OnScanAdvertisementFilterChanged -> {
                _deviceState.update { state ->
                    state.copy(scanAdvertisementFilter = event.value)
                }
            }

            is UiEvent.OnConnectClick -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.connect(device.peripheral)
                            // Update state to show connecting/connected
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(connected = true, peripheral = device.peripheral)
                                state.copy(
                                    devices = HashMap(updateDevices),
                                    selectedDeviceId = event.macId
                                )
                            }
                        } catch (e: Exception) {
                            println("Failed to connect: ${e.message}")
                        }
                    }
                }
            }

            is UiEvent.OnDisconnectClick -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.disconnect(device.peripheral)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(connected = false)
                                state.copy(
                                    devices = HashMap(updateDevices),
                                    selectedDeviceId = if (state.selectedDeviceId == event.macId) null else state.selectedDeviceId
                                )
                            }
                        } catch (e: Exception) {
                            println("Failed to disconnect: ${e.message}")
                        }
                    }
                }
            }

            is UiEvent.OnDeviceSelected -> {
                _deviceState.update { it.copy(selectedDeviceId = event.macId) }
                // Discover services when device detail is opened
                _deviceState.value.devices[event.macId]?.let { device ->
                    if (device.connected && device.peripheral.services.isEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                // Add a small delay to ensure peripheral is fully connected on iOS
                                kotlinx.coroutines.delay(500)
                                // Check connection state before discovering
                                val state = blueFalcon.connectionState(device.peripheral)
                                if (state == dev.bluefalcon.core.BluetoothPeripheralState.Connected) {
                                    blueFalcon.discoverServices(device.peripheral)
                                    // Wait for services to be populated, then discover characteristics
                                    kotlinx.coroutines.delay(1000)
                                    device.peripheral.services.forEach { service ->
                                        blueFalcon.discoverCharacteristics(device.peripheral, service)
                                    }
                                }
                            } catch (e: Exception) {
                                println("Failed to discover services: ${e.message}")
                            }
                        }
                    }
                }
            }

            UiEvent.OnNavigateBack -> {
                _deviceState.update { it.copy(selectedDeviceId = null) }
            }

            is UiEvent.OnRefreshDevice -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Re-discover services to refresh data
                            blueFalcon.discoverServices(device.peripheral)
                            // Wait for services, then discover characteristics
                            kotlinx.coroutines.delay(1000)
                            device.peripheral.services.forEach { service ->
                                blueFalcon.discoverCharacteristics(device.peripheral, service)
                            }
                        } catch (e: Exception) {
                            println("Failed to refresh device: ${e.message}")
                        }
                    }
                }
            }

            is UiEvent.OnReadCharacteristic -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.readCharacteristic(device.peripheral, event.characteristic)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                                state.copy(devices = HashMap(updateDevices))
                            }
                        } catch (e: Exception) {
                            println("Failed to read characteristic: ${e.message}")
                        }
                    }
                }
            }
            is UiEvent.OnWriteCharacteristic -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.writeCharacteristic(device.peripheral, event.characteristic, event.value)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                                state.copy(devices = HashMap(updateDevices))
                            }
                        } catch (e: Exception) {
                            println("Failed to write characteristic: ${e.message}")
                        }
                    }
                }
            }
            is UiEvent.OnToggleNotify -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.notifyCharacteristic(device.peripheral, event.characteristic, !event.characteristic.isNotifying)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                                state.copy(devices = HashMap(updateDevices))
                            }
                        } catch (e: Exception) {
                            println("Failed to toggle notify: ${e.message}")
                        }
                    }
                }
            }
            is UiEvent.OnChangeMtu -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.changeMTU(device.peripheral, event.mtuSize)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(
                                    mtuStatus = "MTU updated",
                                    updateCount = device.updateCount + 1
                                )
                                state.copy(devices = HashMap(updateDevices))
                            }
                        } catch (e: Exception) {
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(
                                    mtuStatus = "MTU update failed: ${e.message}",
                                    updateCount = device.updateCount + 1
                                )
                                state.copy(devices = HashMap(updateDevices))
                            }
                        }
                    }
                }
            }
            is UiEvent.OnReadDescriptor -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.readDescriptor(device.peripheral, event.characteristic, event.descriptor)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                                state.copy(devices = HashMap(updateDevices))
                            }
                        } catch (e: Exception) {
                            println("Failed to read descriptor: ${e.message}")
                        }
                    }
                }
            }

            is UiEvent.OnStartFota -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val messages = fotaPlugin.startUpdate(device.peripheral, event.firmwareData)
                            // Write each SMP message to the SMP characteristic
                            val smpChar = findSmpCharacteristic(device)
                            if (smpChar != null) {
                                // Enable notifications on SMP characteristic
                                blueFalcon.notifyCharacteristic(device.peripheral, smpChar, true)
                                // Write the first chunk
                                if (messages.isNotEmpty()) {
                                    blueFalcon.writeCharacteristic(
                                        device.peripheral,
                                        smpChar,
                                        messages.first()
                                    )
                                }
                            } else {
                                println("SMP characteristic not found on device")
                            }
                        } catch (e: Exception) {
                            println("Failed to start FOTA: ${e.message}")
                        }
                    }
                }
            }

            is UiEvent.OnCancelFota -> {
                fotaPlugin.cancelUpdate()
            }

            is UiEvent.OnCloneDevice -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId] = device.copy(cloneInProgress = true)
                        state.copy(devices = HashMap(updateDevices))
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Ensure characteristics are discovered for all services
                            // (required on iOS/macOS where discoverServices doesn't include characteristics)
                            device.peripheral.services.forEach { service ->
                                if (service.characteristics.isEmpty()) {
                                    blueFalcon.discoverCharacteristics(device.peripheral, service)
                                }
                            }
                            // Allow time for async characteristic discovery to complete
                            kotlinx.coroutines.delay(1500)

                            val clone = clonePlugin.cloneDevice(device.peripheral, blueFalcon.engine)
                            val json = clonePlugin.exportToJson(clone)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(cloneInProgress = false)
                                state.copy(
                                    devices = HashMap(updateDevices),
                                    cloneResultJson = json,
                                    currentClone = clone
                                )
                            }
                        } catch (e: Exception) {
                            println("Failed to clone device: ${e.message}")
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(cloneInProgress = false)
                                state.copy(
                                    devices = HashMap(updateDevices),
                                    cloneResultJson = "Error: ${e.message}"
                                )
                            }
                        }
                    }
                }
            }

            UiEvent.OnDismissCloneResult -> {
                _deviceState.update { it.copy(cloneResultJson = null) }
            }

            is UiEvent.OnStartBroadcast -> {
                val clone = event.clone
                _deviceState.update { it.copy(cloneResultJson = null) }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        broadcastPlugin.startBroadcast(clone, advertiser)
                    } catch (e: Exception) {
                        println("Failed to start broadcast: ${e.message}")
                    }
                }
            }

            UiEvent.OnStopBroadcast -> {
                CoroutineScope(Dispatchers.IO).launch {
                    broadcastPlugin.stopBroadcast()
                }
            }
        }
    }

    private fun buildDevices(
        peripherals: Set<dev.bluefalcon.core.BluetoothPeripheral>,
        previousDevices: Map<String, EnhancedBluetoothPeripheral>
    ): HashMap<String, EnhancedBluetoothPeripheral> {
        val updatedDevices = HashMap<String, EnhancedBluetoothPeripheral>(peripherals.size)

        peripherals.forEach { peripheral ->
            val existingDevice = previousDevices[peripheral.uuid]
            val mfData = peripheral.manufacturerData.mapValues { (_, bytes) ->
                bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
            }
            updatedDevices[peripheral.uuid] = EnhancedBluetoothPeripheral(
                connected = existingDevice?.connected ?: false,
                peripheral = peripheral,
                mtuStatus = existingDevice?.mtuStatus,
                notificationData = existingDevice?.notificationData ?: emptyMap(),
                fotaState = existingDevice?.fotaState ?: FotaState.Idle,
                rssi = peripheral.rssi ?: existingDevice?.rssi,
                manufacturerData = mfData.ifEmpty { existingDevice?.manufacturerData ?: emptyMap() }
            )
        }
        return updatedDevices
    }

    private fun findSmpCharacteristic(
        device: EnhancedBluetoothPeripheral
    ): dev.bluefalcon.core.BluetoothCharacteristic? {
        val smpServiceUuid = NordicFotaPlugin.SMP_SERVICE_UUID
        val smpCharUuid = NordicFotaPlugin.SMP_CHARACTERISTIC_UUID
        for (service in device.peripheral.services) {
            if (service.uuid.toString().equals(smpServiceUuid, ignoreCase = true)) {
                for (char in service.characteristics) {
                    if (char.uuid.toString().equals(smpCharUuid, ignoreCase = true)) {
                        return char
                    }
                }
            }
        }
        return null
    }
}

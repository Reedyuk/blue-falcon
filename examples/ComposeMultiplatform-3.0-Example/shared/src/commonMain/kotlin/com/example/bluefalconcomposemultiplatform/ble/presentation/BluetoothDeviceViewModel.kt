package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.DisconnectReason
import dev.bluefalcon.core.PeripheralConnectionState
import dev.bluefalcon.core.ServiceDiscoveryPhase
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.plugins.bonding.BondResult
import dev.bluefalcon.plugins.bonding.BondingPlugin
import dev.bluefalcon.plugins.broadcast.DeviceBroadcastPlugin
import dev.bluefalcon.plugins.clone.CloneConfig
import dev.bluefalcon.plugins.clone.DeviceClonePlugin
import dev.bluefalcon.plugins.nordicfota.FotaState
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin
import dev.bluefalcon.plugins.proximity.ProximityPlugin
import dev.bluefalcon.plugins.proximity.ProximityZone
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
<<<<<<< HEAD
    private val proximityPlugin: ProximityPlugin,
=======
    private val bondingPlugin: BondingPlugin,
>>>>>>> origin/master
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
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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

        // Collect smoothed RSSI and proximity zone from ProximityPlugin instead of raw rssiUpdates
        viewModelScope.launch(Dispatchers.IO) {
            proximityPlugin.proximityReadings.collect { readings ->
                _deviceState.update { state ->
                    val updatedDevices = state.devices.toMutableMap()
                    var changed = false
                    readings.forEach { (uuid, reading) ->
                        updatedDevices[uuid]?.let { device ->
                            val updated = device.copy(
                                rssi = reading.smoothedRssi,
                                proximityZone = reading.zone,
                                estimatedDistanceMeters = reading.estimatedDistanceMeters
                            )
                            if (updated != device) {
                                updatedDevices[uuid] = updated
                                changed = true
                            }
                        }
                    }
                    if (changed) state.copy(devices = HashMap(updatedDevices)) else state
                }
            }
        }

        // Collect FOTA state changes and update the relevant device
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
            broadcastPlugin.broadcastState.collect { broadcastState ->
                _deviceState.update { it.copy(broadcastState = broadcastState) }
            }
        }

        // Collect bond state updates from the bonding plugin
        viewModelScope.launch(Dispatchers.IO) {
            bondingPlugin.bondStates.collect { bondStates ->
                _deviceState.update { state ->
                    val updatedDevices = state.devices.toMutableMap()
                    var changed = false
                    bondStates.forEach { (peripheralId, bondState) ->
                        updatedDevices[peripheralId]?.let { device ->
                            val updated = device.copy(
                                bondState = bondState.state,
                                bondCapability = bondState.capability,
                            )
                            if (updated != device) {
                                updatedDevices[peripheralId] = updated
                                changed = true
                            }
                        }
                    }
                    if (changed) state.copy(devices = HashMap(updatedDevices)) else state
                }
            }
        }

        // Collect the structured per-peripheral connection state machine (ADR 0008) so the UI
        // reflects actual BLE state, including synchronous connect failures that never emit a
        // raw connectionStateUpdates event at all (engine.connect() throwing before any platform
        // callback fires). This is a StateFlow, so it also derives an authoritative snapshot for
        // every peripheral currently known to the UI on each emission - no polling required.
        viewModelScope.launch(Dispatchers.IO) {
            blueFalcon.connectionStates.collect { states ->
                _deviceState.update { state ->
                    val updatedDevices = state.devices.toMutableMap()
                    var changed = false
                    state.devices.forEach { (peripheralId, device) ->
                        val peripheralState = states[peripheralId] ?: return@forEach
                        val updated = device.applyConnectionState(peripheralState)
                        if (updated != device) {
                            updatedDevices[peripheralId] = updated
                            changed = true
                        }
                    }
                    if (changed) state.copy(devices = HashMap(updatedDevices)) else state
                }
            }
        }

        // React to GATT service/characteristic discovery so we never need arbitrary delays.
        // ServicesDiscovered: kick off characteristic discovery for each service.
        // CharacteristicsDiscovered: force a UI refresh so the detail screen updates immediately.
        viewModelScope.launch(Dispatchers.IO) {
            blueFalcon.serviceDiscoveryUpdates.collect { update ->
                when (update.phase) {
                    ServiceDiscoveryPhase.ServicesDiscovered -> {
                        try {
                            update.peripheral.services.forEach { service ->
                                blueFalcon.discoverCharacteristics(update.peripheral, service)
                            }
                        } catch (e: Exception) {
                            println("Failed to discover characteristics: ${e.message}")
                        }
                    }
                    ServiceDiscoveryPhase.CharacteristicsDiscovered -> {
                        _deviceState.update { state ->
                            val updatedDevices = state.devices.toMutableMap()
                            updatedDevices[update.peripheral.uuid]?.let { device ->
                                updatedDevices[update.peripheral.uuid] =
                                    device.copy(updateCount = device.updateCount + 1)
                            }
                            state.copy(devices = HashMap(updatedDevices))
                        }
                    }
                }
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
                    // Show a loading spinner on the connect button while the connection is
                    // in flight. Do NOT navigate to the detail screen here — the user must
                    // tap the row/cell to navigate once connected. The connected flag (and
                    // the connecting spinner) are cleared reactively via the
                    // connectionStateUpdates flow once the platform confirms the outcome.
                    _deviceState.update { state ->
                        val updatedDevices = state.devices.toMutableMap()
                        updatedDevices[event.macId] = device.copy(connecting = true)
                        state.copy(devices = HashMap(updatedDevices))
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.connect(device.peripheral)
                        } catch (e: Exception) {
                            println("Failed to connect: ${e.message}")
                            _deviceState.update { state ->
                                val updatedDevices = state.devices.toMutableMap()
                                updatedDevices[event.macId]?.let { current ->
                                    updatedDevices[event.macId] = current.copy(connecting = false)
                                }
                                state.copy(devices = HashMap(updatedDevices))
                            }
                        }
                    }
                }
            }

            is UiEvent.OnDisconnectClick -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    _deviceState.update { state ->
                        val updatedDevices = state.devices.toMutableMap()
                        updatedDevices[event.macId] = device.copy(connecting = true)
                        state.copy(devices = HashMap(updatedDevices))
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            blueFalcon.disconnect(device.peripheral)
                            _deviceState.update { state ->
                                val updateDevices = state.devices.toMutableMap()
                                updateDevices[event.macId] = device.copy(connected = false, connecting = false)
                                state.copy(
                                    devices = HashMap(updateDevices),
                                    selectedDeviceId = if (state.selectedDeviceId == event.macId) null else state.selectedDeviceId
                                )
                            }
                        } catch (e: Exception) {
                            println("Failed to disconnect: ${e.message}")
                            _deviceState.update { state ->
                                val updatedDevices = state.devices.toMutableMap()
                                updatedDevices[event.macId]?.let { current ->
                                    updatedDevices[event.macId] = current.copy(connecting = false)
                                }
                                state.copy(devices = HashMap(updatedDevices))
                            }
                        }
                    }
                }
            }

            is UiEvent.OnDeviceSelected -> {
                _deviceState.update { it.copy(selectedDeviceId = event.macId) }
                // Kick off service discovery if not already done.
                // Characteristic discovery and UI refresh are driven by serviceDiscoveryUpdates.
                _deviceState.value.devices[event.macId]?.let { device ->
                    if (device.connected && device.peripheral.services.isEmpty()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                // peripheralState() (ADR 0008) reflects the same authoritative
                                // state as connectionStates, so this check is consistent with the
                                // connected/connecting flags already driving the UI.
                                val state = blueFalcon.peripheralState(device.peripheral)
                                if (state is PeripheralConnectionState.Connected || state is PeripheralConnectionState.Ready) {
                                    blueFalcon.discoverServices(device.peripheral)
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
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            // Re-discover services; characteristic discovery is driven by serviceDiscoveryUpdates.
                            blueFalcon.discoverServices(device.peripheral)
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

            is UiEvent.OnRequestBond -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    _deviceState.update { state ->
                        val updatedDevices = state.devices.toMutableMap()
                        updatedDevices[event.macId] = device.copy(bondInProgress = true)
                        state.copy(devices = HashMap(updatedDevices))
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        val result = bondingPlugin.requestBond(device.peripheral)
                        val message = when (result) {
                            is BondResult.Bonded -> "Bonded successfully"
                            is BondResult.Failed -> "Bond failed: ${result.cause.message}"
                            is BondResult.Unsupported -> "Bonding not supported on this platform"
                            is BondResult.TimedOut -> "Bond request timed out"
                            is BondResult.Unbonded -> null
                        }
                        _deviceState.update { state ->
                            val updatedDevices = state.devices.toMutableMap()
                            updatedDevices[event.macId]?.let { current ->
                                updatedDevices[event.macId] = current.copy(
                                    bondInProgress = false,
                                    bondStatus = message
                                )
                            }
                            state.copy(devices = HashMap(updatedDevices))
                        }
                    }
                }
            }

            is UiEvent.OnRequestUnbond -> {
                _deviceState.value.devices[event.macId]?.let { device ->
                    _deviceState.update { state ->
                        val updatedDevices = state.devices.toMutableMap()
                        updatedDevices[event.macId] = device.copy(bondInProgress = true)
                        state.copy(devices = HashMap(updatedDevices))
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        val result = bondingPlugin.requestUnbond(device.peripheral)
                        val message = when (result) {
                            is BondResult.Unbonded -> "Unbonded successfully"
                            is BondResult.Failed -> "Unbond failed: ${result.cause.message}"
                            is BondResult.Unsupported -> "Unbonding not supported on this platform"
                            is BondResult.TimedOut -> "Unbond request timed out"
                            is BondResult.Bonded -> null
                        }
                        _deviceState.update { state ->
                            val updatedDevices = state.devices.toMutableMap()
                            updatedDevices[event.macId]?.let { current ->
                                updatedDevices[event.macId] = current.copy(
                                    bondInProgress = false,
                                    bondStatus = message
                                )
                            }
                            state.copy(devices = HashMap(updatedDevices))
                        }
                    }
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
                manufacturerData = mfData.ifEmpty { existingDevice?.manufacturerData ?: emptyMap() },
                connectionError = existingDevice?.connectionError,
                bondState = existingDevice?.bondState ?: dev.bluefalcon.core.BlueFalconBondState.None,
                bondCapability = existingDevice?.bondCapability ?: blueFalcon.centralCapabilities.bondCapability,
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

/**
 * Folds a [PeripheralConnectionState] (ADR 0008) into this device's UI-facing
 * connected/connecting/connectionError fields.
 */
private fun EnhancedBluetoothPeripheral.applyConnectionState(
    state: PeripheralConnectionState
): EnhancedBluetoothPeripheral = when (state) {
    is PeripheralConnectionState.Connecting ->
        copy(connecting = true, connected = false, connectionError = null)
    is PeripheralConnectionState.Connected, is PeripheralConnectionState.Ready ->
        copy(connecting = false, connected = true, connectionError = null)
    is PeripheralConnectionState.Disconnecting ->
        copy(connecting = true)
    is PeripheralConnectionState.Disconnected ->
        copy(
            connecting = false,
            connected = false,
            connectionError = state.reason?.toDisplayMessage()
        )
}

/** Human-readable message for a [DisconnectReason], or `null` for an expected user-initiated disconnect. */
private fun DisconnectReason.toDisplayMessage(): String? = when (this) {
    is DisconnectReason.UserInitiated -> null
    is DisconnectReason.ConnectFailed -> "Failed to connect: ${cause.message ?: cause::class.simpleName}"
    is DisconnectReason.Unexpected -> "Connection lost unexpectedly"
}

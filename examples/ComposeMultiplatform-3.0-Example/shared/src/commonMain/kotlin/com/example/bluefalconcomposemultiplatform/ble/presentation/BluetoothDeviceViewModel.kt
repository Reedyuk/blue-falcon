package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.core.BlueFalcon
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
    private val blueFalcon: BlueFalcon
): ViewModel() {

    private val _deviceState: MutableStateFlow<BluetoothDeviceState> = MutableStateFlow(BluetoothDeviceState())
    val deviceState: StateFlow<BluetoothDeviceState> get() = _deviceState

    init {
        // Collect peripherals from BlueFalcon's StateFlow
        CoroutineScope(Dispatchers.IO).launch {
            blueFalcon.peripherals.collect { peripherals ->
                val uniqueKeys = _deviceState.value.devices.keys.toList()
                val filteredPeripheral = peripherals.filter { !uniqueKeys.contains(it.uuid) }
                filteredPeripheral.map { peripheral ->
                    _deviceState.update {
                        val updateDevices = it.devices.toMutableMap()
                        updateDevices[peripheral.uuid] = EnhancedBluetoothPeripheral(false, peripheral)
                        it.copy(
                            devices = HashMap(updateDevices)
                        )
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
                        blueFalcon.scan()
                        _deviceState.update { it.copy(isScanning = true) }
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
                                    _deviceState.update { state ->
                                        val updateDevices = state.devices.toMutableMap()
                                        updateDevices[event.macId] = device.copy(peripheral = device.peripheral)
                                        state.copy(devices = HashMap(updateDevices))
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
        }
    }
}

package com.example.bluefalconcomposemultiplatform.ble.presentation

import com.example.bluefalconcomposemultiplatform.ble.data.BleDelegate
import com.example.bluefalconcomposemultiplatform.ble.data.DeviceEvent
import dev.bluefalcon.BlueFalcon
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BluetoothDeviceViewModel(
    private val blueFalcon: BlueFalcon,
    delegate: BleDelegate = BleDelegate()
): ViewModel() {

    private val _deviceState: MutableStateFlow<BluetoothDeviceState> = MutableStateFlow(BluetoothDeviceState())
    val deviceState: StateFlow<BluetoothDeviceState> get() = _deviceState

    init {
        delegate.setListener { event ->
            when(event) {
                is DeviceEvent.OnDeviceConnected -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let {
                            updateDevices[event.macId] = it.copy(connected = true)
                        }
                        state.copy(
                            devices = HashMap(updateDevices),
                            selectedDeviceId = event.macId
                        )
                    }
                }

                is DeviceEvent.OnDeviceDisconnected -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let {
                            updateDevices[event.macId] = it.copy(connected = false)
                        }
                        state.copy(
                            devices = HashMap(updateDevices),
                            selectedDeviceId = if (state.selectedDeviceId == event.macId) null else state.selectedDeviceId
                        )
                    }
                }

                is DeviceEvent.OnServicesDiscovered -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let {
                            updateDevices[event.macId] = it.copy(peripheral = event.peripheral)
                        }
                        state.copy(devices = HashMap(updateDevices))
                    }
                }

                is DeviceEvent.OnCharacteristicValueChanged -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let { device ->
                            updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                        }
                        state.copy(devices = HashMap(updateDevices))
                    }
                }

                is DeviceEvent.OnNotificationStateChanged -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let { device ->
                            updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                        }
                        state.copy(devices = HashMap(updateDevices))
                    }
                }

                is DeviceEvent.OnRssiUpdated -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let { device ->
                            updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                        }
                        state.copy(devices = HashMap(updateDevices))
                    }
                }

                is DeviceEvent.OnMtuUpdated -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let { device ->
                            updateDevices[event.macId] = device.copy(
                                mtuStatus = if (event.status == 0) "MTU updated" else "MTU update failed (status: ${event.status})",
                                updateCount = device.updateCount + 1
                            )
                        }
                        state.copy(devices = HashMap(updateDevices))
                    }
                }

                is DeviceEvent.OnDescriptorRead -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let { device ->
                            updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                        }
                        state.copy(devices = HashMap(updateDevices))
                    }
                }

                is DeviceEvent.OnWriteCharacteristicResult -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let { device ->
                            updateDevices[event.macId] = device.copy(updateCount = device.updateCount + 1)
                        }
                        state.copy(devices = HashMap(updateDevices))
                    }
                }
            }
        }
        blueFalcon.delegates.add(delegate)
        CoroutineScope(Dispatchers.IO).launch {
            blueFalcon.peripherals.collect { peripherals ->
                _deviceState.update { currentState ->
                    val updatedDevices = currentState.devices.toMutableMap()
                    
                    peripherals.forEach { peripheral ->
                        val existingDevice = updatedDevices[peripheral.uuid]
                        if (existingDevice == null) {
                            // New peripheral - add it
                            updatedDevices[peripheral.uuid] = EnhancedBluetoothPeripheral(
                                connected = false,
                                peripheral = peripheral
                            )
                        }
                        // Note: Updates to existing peripherals (like service discovery) 
                        // are handled by delegate callbacks to preserve connection state
                    }
                    
                    currentState.copy(devices = HashMap(updatedDevices))
                }
            }
        }
    }

    fun onEvent(event: UiEvent) {
        when(event) {
            UiEvent.OnScanClick -> {
                blueFalcon.scan()
                _deviceState.update { it.copy(isScanning = true) }
            }

            UiEvent.OnStopScanClick -> {
                blueFalcon.stopScanning()
                _deviceState.update { it.copy(isScanning = false) }
            }

            is UiEvent.OnConnectClick -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.connect(it.peripheral, false)
                }
            }

            is UiEvent.OnDisconnectClick -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.disconnect(it.peripheral)
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
                                blueFalcon.discoverServices(device.peripheral)
                                // Service discovery result will be handled by delegate callback
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
                    // Re-discover services to refresh data
                    blueFalcon.discoverServices(device.peripheral)
                }
            }

            is UiEvent.OnReadCharacteristic -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.readCharacteristic(it.peripheral, event.characteristic)
                }
            }
            is UiEvent.OnWriteCharacteristic -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.writeCharacteristic(it.peripheral, event.characteristic, event.value, null)
                }
            }
            is UiEvent.OnToggleNotify -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.notifyCharacteristic(it.peripheral, event.characteristic, !event.characteristic.isNotifying)
                }
            }
            is UiEvent.OnChangeMtu -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.changeMTU(it.peripheral, event.mtuSize)
                }
            }
            is UiEvent.OnReadDescriptor -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.readDescriptor(it.peripheral, event.characteristic, event.descriptor)
                }
            }
        }
    }
}
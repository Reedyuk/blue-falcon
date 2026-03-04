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
                        // Trigger a UI refresh so the descriptor value is displayed
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
            }

            UiEvent.OnNavigateBack -> {
                _deviceState.update { it.copy(selectedDeviceId = null) }
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
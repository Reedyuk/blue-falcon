package com.example.bluefalconcomposemultiplatform.ble.presentation

import com.example.bluefalconcomposemultiplatform.ble.data.BleDelegate
import com.example.bluefalconcomposemultiplatform.ble.data.DeviceEvent
import dev.bluefalcon.engine.blueFalconEngine
import dev.bluefalcon.engine.WriteType
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.engine.BluetoothAction
import dev.bluefalcon.engine.BluetoothActionResult
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BluetoothDeviceViewModel(
    applicationContext: ApplicationContext,
    delegate: BleDelegate = BleDelegate()
): ViewModel() {

    private val _deviceState: MutableStateFlow<BluetoothDeviceState> = MutableStateFlow(BluetoothDeviceState())
    val deviceState: StateFlow<BluetoothDeviceState> get() = _deviceState

    private val blueFalconEngine = blueFalconEngine(
        context = applicationContext,
        delegate = delegate
    )

    init {
        delegate.setListener {event ->
            when(event) {
                is DeviceEvent.OnDeviceDiscovered -> {
                    _deviceState.update {
                        val updateDevices = it.devices.toMutableMap()
                        updateDevices[event.macId] = EnhancedBluetoothPeripheral(false, event.device)
                        it.copy(devices = HashMap(updateDevices))
                    }
                }
                is DeviceEvent.OnDeviceConnected -> {
                    _deviceState.update { state ->
                        val updateDevices = state.devices.toMutableMap()
                        updateDevices[event.macId]?.let {
                            updateDevices[event.macId] = it.copy(connected = true)
                        }
                        state.copy(
                            devices = HashMap(updateDevices)
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
                    if (_deviceState.value.isScanning) {
                        blueFalconEngine.execute(BluetoothAction.StopScan)
                    } else {
                        CoroutineScope(Dispatchers.IO).launch {
                            blueFalconEngine.execute(BluetoothAction.Scan()).collect {
                                when (it) {
                                    is BluetoothActionResult.Scan -> {
                                        _deviceState.update { state ->
                                            val updateDevices = state.devices.toMutableMap()
                                            updateDevices[it.device.uuid] = EnhancedBluetoothPeripheral(
                                                false,
                                                it.device
                                            )
                                            state.copy(devices = HashMap(updateDevices))
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                    _deviceState.update { state ->
                        state.copy(isScanning = !state.isScanning)
                    }
                }
            }

            is UiEvent.OnConnectClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    blueFalconEngine.execute(BluetoothAction.Connect(event.macId)).collect { connectState ->
                        when (connectState) {
                            is BluetoothActionResult.Connect -> {
                                _deviceState.update { state ->
                                    val updateDevices = state.devices.toMutableMap()
                                    updateDevices[event.macId]?.let {
                                        updateDevices[event.macId] = it.copy(connected = true)
                                    }
                                    state.copy(devices = HashMap(updateDevices))
                                }
                            }
                            else -> {}
                        }
                        blueFalconEngine.execute(BluetoothAction.DiscoverServices(event.macId)).collect { discoverServiceState ->
                            when (discoverServiceState) {
                                is BluetoothActionResult.DiscoverServices -> {
                                    discoverServiceState.device.services.forEach { service ->
                                        blueFalconEngine.execute(
                                            BluetoothAction.DiscoverCharacteristics(event.macId, service.uuid)
                                        ).collect { discoverState ->
                                            when (discoverState) {
                                                is BluetoothActionResult.DiscoverCharacteristics -> {
                                                    _deviceState.update { state ->
                                                        val updateDevices =
                                                            state.devices.toMutableMap()
                                                        updateDevices[event.macId]?.let {
                                                            updateDevices[event.macId] = it.copy(peripheral = discoverState.device)
                                                        }
                                                        state.copy(devices = HashMap(updateDevices))
                                                    }
                                                }

                                                else -> {}
                                            }
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            is UiEvent.OnDisconnectClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let {
                        blueFalconEngine.execute(BluetoothAction.Disconnect(it.peripheral.uuid))
                    }
                }
            }

            is UiEvent.OnReadCharacteristic -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let {
                        blueFalconEngine.execute(
                            BluetoothAction.ReadCharacteristic(
                                it.peripheral.uuid,
                                event.characteristic.uuid
                            )
                        )
                    }
                }
            }
            is UiEvent.OnWriteCharacteristic -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let {
                        blueFalconEngine.execute(
                            BluetoothAction.WriteCharacteristic(
                                it.peripheral.uuid,
                                event.characteristic.uuid,
                                event.value.encodeToByteArray(),
                                WriteType.writeTypeDefault
                            )
                        )
                    }
                }
            }
        }
    }
}
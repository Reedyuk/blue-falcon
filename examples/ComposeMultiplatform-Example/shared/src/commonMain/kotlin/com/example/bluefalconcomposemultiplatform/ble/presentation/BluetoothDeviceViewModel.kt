package com.example.bluefalconcomposemultiplatform.ble.presentation

import com.example.bluefalconcomposemultiplatform.ble.data.BleDelegate
import com.example.bluefalconcomposemultiplatform.ble.data.DeviceEvent
import dev.bluefalcon.engine.blueFalconEngine
import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.engine.BluetoothAction
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
                        it.copy(
                            devices = HashMap(updateDevices)
                        )
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
        blueFalcon.delegates.add(delegate)
//        CoroutineScope(Dispatchers.IO).launch {
//            blueFalcon.peripherals.collect { peripherals ->
//                val uniqueKeys = _deviceState.value.devices.keys.toList()
//                val filteredPeripheral = peripherals.filter { !uniqueKeys.contains(it.uuid) }
//                filteredPeripheral.map { peripheral ->
//                    _deviceState.update {
//                        val updateDevices = it.devices.toMutableMap()
//                        updateDevices[peripheral.uuid] = EnhancedBluetoothPeripheral(false, peripheral)
//                        it.copy(
//                            devices = HashMap(updateDevices)
//                        )
//                    }
//                }
//            }
//        }
    }

    fun onEvent(event: UiEvent) {
        when(event) {
            UiEvent.OnScanClick -> {
//                blueFalcon.scan()
                CoroutineScope(Dispatchers.IO).launch {
                    blueFalconEngine.execute(BluetoothAction.Scan())
                }
            }

            is UiEvent.OnConnectClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let {
                        blueFalconEngine.execute(BluetoothAction.Connect(it.peripheral.uuid))
                        //                    blueFalcon.connect(it.peripheral, false)
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
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.readCharacteristic(it.peripheral, event.characteristic)
                }
            }
            is UiEvent.OnWriteCharacteristic -> {
                _deviceState.value.devices[event.macId]?.let {
                    blueFalcon.writeCharacteristic(it.peripheral, event.characteristic, event.value, null)
                }
            }
        }
    }
}
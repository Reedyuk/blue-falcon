package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PeripheralServerViewModel(
    runtime: PeripheralExampleRuntime?,
) : ViewModel() {
    private val controller = PeripheralEchoController(runtime, viewModelScope)
    val state: StateFlow<PeripheralServerState> = controller.state

    fun start() = viewModelScope.launch {
        controller.start()
    }

    fun stop() = viewModelScope.launch {
        controller.stop()
    }

    fun sendNotification() = viewModelScope.launch {
        controller.sendNotification()
    }

    fun setPayloadText(value: String) = controller.setPayloadText(value)
}

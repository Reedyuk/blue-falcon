package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hosts a single local GATT server at a time, switchable between the Echo demo
 * profile and the standard Heart Rate Monitor profile. Switching profiles is only
 * allowed while the server is stopped (see [PeripheralServerState.canSwitchProfile]).
 */
class PeripheralServerViewModel(
    private val runtime: PeripheralExampleRuntime?,
) : ViewModel() {
    private var controllerScope = newControllerScope()
    private var bondingRequiredPreference = false
    private var bondOnHeartRateReadPreference = false
    private var active: ActiveController =
        ActiveController.Echo(PeripheralEchoController(runtime, controllerScope))

    private val mutableState = MutableStateFlow(active.state.value)
    val state: StateFlow<PeripheralServerState> = mutableState.asStateFlow()

    private var forwardingJob: Job = viewModelScope.launch {
        active.state.collect { current -> mutableState.value = current }
    }

    fun start() = viewModelScope.launch {
        active.start()
    }

    fun stop() = viewModelScope.launch {
        active.stop()
    }

    fun sendNotification() = viewModelScope.launch {
        (active as? ActiveController.Echo)?.controller?.sendNotification()
    }

    fun setPayloadText(value: String) {
        (active as? ActiveController.Echo)?.controller?.setPayloadText(value)
    }

    fun toggleHeartRateSimulation() {
        (active as? ActiveController.HeartRate)?.controller?.toggleHeartRateSimulation()
    }

    fun setBondingRequired(required: Boolean) {
        bondingRequiredPreference = required
        (active as? ActiveController.HeartRate)?.controller?.setBondingRequired(required)
    }

    fun setBondOnHeartRateRead(required: Boolean) {
        bondOnHeartRateReadPreference = required
        (active as? ActiveController.HeartRate)?.controller?.setBondOnHeartRateRead(required)
    }

    fun selectProfile(profile: PeripheralProfile) {
        if (!mutableState.value.canSwitchProfile) return
        if (profile == mutableState.value.profile) return

        viewModelScope.launch {
            forwardingJob.cancelAndJoin()
            controllerScope.cancel()
            controllerScope = newControllerScope()
            active = when (profile) {
                PeripheralProfile.ECHO ->
                    ActiveController.Echo(PeripheralEchoController(runtime, controllerScope))

                PeripheralProfile.HEART_RATE_MONITOR ->
                    ActiveController.HeartRate(
                        PeripheralHeartRateController(
                            runtime = runtime,
                            scope = controllerScope,
                            initialBondingRequired = bondingRequiredPreference,
                            initialBondOnHeartRateRead = bondOnHeartRateReadPreference,
                        ),
                    )
            }
            mutableState.value = active.state.value
            forwardingJob = viewModelScope.launch {
                active.state.collect { current -> mutableState.value = current }
            }
        }
    }

    private fun newControllerScope(): CoroutineScope =
        CoroutineScope(viewModelScope.coroutineContext + Job(viewModelScope.coroutineContext[Job]))
}

private sealed interface ActiveController {
    val state: StateFlow<PeripheralServerState>

    suspend fun start()
    suspend fun stop()

    class Echo(val controller: PeripheralEchoController) : ActiveController {
        override val state: StateFlow<PeripheralServerState> = controller.state
        override suspend fun start() = controller.start()
        override suspend fun stop() = controller.stop()
    }

    class HeartRate(val controller: PeripheralHeartRateController) : ActiveController {
        override val state: StateFlow<PeripheralServerState> = controller.state
        override suspend fun start() = controller.start()
        override suspend fun stop() = controller.stop()
    }
}

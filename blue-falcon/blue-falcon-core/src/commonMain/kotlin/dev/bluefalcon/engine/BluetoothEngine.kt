package dev.bluefalcon.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

interface BluetoothEngine : CoroutineScope {
    val config: BluetoothEngineConfig
    val dispatcher: CoroutineDispatcher
    // config object

    // this will be the bluetooth action.
    suspend fun execute(action: BluetoothAction)
}

package dev.bluefalcon.peripheral

import kotlinx.coroutines.CoroutineScope

open class PeripheralPluginConfig

interface PeripheralPlugin<out T> {
    fun install(peripheral: BlueFalconPeripheral, scope: CoroutineScope): T

    suspend fun close()
}

interface PeripheralPluginFactory<C : PeripheralPluginConfig, T> {
    fun createConfig(): C

    fun create(config: C): PeripheralPlugin<T>
}

interface PeripheralPluginRegistry {
    fun <C : PeripheralPluginConfig, T> install(
        factory: PeripheralPluginFactory<C, T>,
        configure: C.() -> Unit = {},
    ): T
}

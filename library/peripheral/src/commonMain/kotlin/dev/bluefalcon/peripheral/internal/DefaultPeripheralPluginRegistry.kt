package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.PeripheralPlugin
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralPluginRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlin.coroutines.CoroutineContext

internal class DefaultPeripheralPluginRegistry(
    private val peripheral: BlueFalconPeripheral,
    coroutineContext: CoroutineContext,
) : PeripheralPluginRegistry {

    private val lock = Any()
    private val pluginJob = SupervisorJob(coroutineContext[Job])
    private val pluginScope = CoroutineScope(coroutineContext.minusKey(Job) + pluginJob)
    private val installedFactories = mutableSetOf<PeripheralPluginFactory<*, *>>()
    private val installedPlugins = mutableListOf<PeripheralPlugin<*>>()
    private val closeCompletion = CompletableDeferred<Throwable?>()
    private var closeStarted = false

    override fun <C : PeripheralPluginConfig, T> install(
        factory: PeripheralPluginFactory<C, T>,
        configure: C.() -> Unit,
    ): T = synchronized(lock) {
        check(!closeStarted) { "Peripheral plugins cannot be installed after close begins" }
        check(installedFactories.add(factory)) { "Peripheral plugin factory is already installed" }
        val plugin = factory.create(factory.createConfig().apply(configure))
        try {
            plugin.install(peripheral, pluginScope).also { installedPlugins += plugin }
        } catch (cause: Throwable) {
            installedFactories.remove(factory)
            throw cause
        }
    }

    suspend fun close() {
        val plugins = synchronized(lock) {
            if (closeStarted) return@synchronized null
            closeStarted = true
            installedPlugins.asReversed().toList().also { installedPlugins.clear() }
        }
        if (plugins == null) {
            closeCompletion.await()?.let { throw it }
            return
        }

        var failure: Throwable? = null
        plugins.forEach { plugin ->
            try {
                plugin.close()
            } catch (cause: Throwable) {
                if (failure == null) {
                    failure = cause
                } else {
                    failure.addSuppressed(cause)
                }
            }
        }
        pluginJob.cancelAndJoin()
        closeCompletion.complete(failure)
        failure?.let { throw it }
    }
}

package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.PeripheralPlugin
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralPluginRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal class DefaultPeripheralPluginRegistry(
    private val peripheral: BlueFalconPeripheral,
    coroutineContext: CoroutineContext,
) : PeripheralPluginRegistry {

    private val lifecycleMutex = Mutex()
    private val pluginJob = SupervisorJob(coroutineContext[Job])
    private val pluginCoroutineContext = coroutineContext.minusKey(Job)
    private val installedFactories = mutableListOf<PeripheralPluginFactory<*, *>>()
    private val installedPlugins = mutableListOf<InstalledPeripheralPlugin>()
    private val closeCompletion = CompletableDeferred<Throwable?>()
    private var closeStarted = false

    override fun <C : PeripheralPluginConfig, T> install(
        factory: PeripheralPluginFactory<C, T>,
        configure: C.() -> Unit,
    ): T {
        check(lifecycleMutex.tryLock()) {
            "Concurrent peripheral plugin installation is not supported"
        }
        try {
            check(!closeStarted) { "Peripheral plugins cannot be installed after close begins" }
            check(installedFactories.none { it === factory }) {
                "Peripheral plugin factory is already installed"
            }
            installedFactories += factory
            var installationJob: Job? = null
            try {
                val plugin = factory.create(factory.createConfig().apply(configure))
                val childJob = SupervisorJob(pluginJob)
                installationJob = childJob
                val installationScope = CoroutineScope(
                    pluginCoroutineContext + childJob,
                )
                return plugin.install(peripheral, installationScope).also {
                    installedPlugins += InstalledPeripheralPlugin(plugin, childJob)
                }
            } catch (cause: Throwable) {
                installationJob?.cancel()
                installedFactories.removeAt(
                    installedFactories.indexOfFirst { it === factory },
                )
                throw cause
            }
        } finally {
            lifecycleMutex.unlock()
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        val plugins = lifecycleMutex.withLock {
            if (closeStarted) return@withLock null
            closeStarted = true
            installedPlugins.asReversed().toList().also {
                installedPlugins.clear()
                installedFactories.clear()
            }
        }
        if (plugins == null) {
            closeCompletion.await()?.let { throw it }
            return@withContext
        }

        var failure: Throwable? = null
        plugins.forEach { installed ->
            installed.job.cancel()
            try {
                installed.plugin.close()
            } catch (cause: Throwable) {
                if (failure == null) {
                    failure = cause
                } else {
                    failure.addSuppressed(cause)
                }
            }
            installed.job.cancelAndJoin()
        }
        pluginJob.cancelAndJoin()
        closeCompletion.complete(failure)
        failure?.let { throw it }
        Unit
    }

    private data class InstalledPeripheralPlugin(
        val plugin: PeripheralPlugin<*>,
        val job: Job,
    )
}

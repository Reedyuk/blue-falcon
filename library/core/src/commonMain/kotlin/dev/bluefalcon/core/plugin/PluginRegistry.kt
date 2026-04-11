package dev.bluefalcon.core.plugin

/**
 * Registry for managing installed plugins
 */
class PluginRegistry {
    @PublishedApi
    internal val plugins = mutableListOf<BlueFalconPlugin>()
    
    /**
     * Install a plugin
     */
    fun <T : BlueFalconPlugin> install(plugin: T, configure: PluginConfig.() -> Unit = {}) {
        val config = PluginConfig().apply(configure)
        plugins.add(plugin)
    }
    
    /**
     * Get all installed plugins
     */
    fun getAll(): List<BlueFalconPlugin> = plugins.toList()
    
    /**
     * Find a plugin by type
     */
    inline fun <reified T : BlueFalconPlugin> find(): T? {
        return plugins.firstOrNull { it is T } as? T
    }
    
    /**
     * Execute scan interceptors
     */
    suspend fun interceptScan(call: ScanCall, proceed: suspend (ScanCall) -> Unit) {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeScan(currentCall)
        }
        proceed(currentCall)
        for (plugin in plugins) {
            plugin.onAfterScan(currentCall)
        }
    }
    
    /**
     * Execute connect interceptors
     */
    suspend fun interceptConnect(call: ConnectCall, proceed: suspend (ConnectCall) -> Result<Unit>): Result<Unit> {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeConnect(currentCall)
        }
        val result = proceed(currentCall)
        for (plugin in plugins) {
            plugin.onAfterConnect(currentCall, result)
        }
        return result
    }
    
    /**
     * Execute read interceptors
     */
    suspend fun interceptRead(call: ReadCall, proceed: suspend (ReadCall) -> Result<ByteArray?>): Result<ByteArray?> {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeRead(currentCall)
        }
        val result = proceed(currentCall)
        for (plugin in plugins) {
            plugin.onAfterRead(currentCall, result)
        }
        return result
    }
    
    /**
     * Execute write interceptors
     */
    suspend fun interceptWrite(call: WriteCall, proceed: suspend (WriteCall) -> Result<Unit>): Result<Unit> {
        var currentCall = call
        for (plugin in plugins) {
            currentCall = plugin.onBeforeWrite(currentCall)
        }
        val result = proceed(currentCall)
        for (plugin in plugins) {
            plugin.onAfterWrite(currentCall, result)
        }
        return result
    }
}

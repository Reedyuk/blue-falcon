package dev.bluefalcon.plugins.caching

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Plugin that caches service and characteristic discovery results.
 * 
 * Usage:
 * ```
 * install(CachingPlugin) {
 *     cacheServices = true
 *     cacheCharacteristics = true
 *     cacheDuration = 5.minutes
 *     invalidateOnDisconnect = true
 * }
 * ```
 */
class CachingPlugin(private val config: Config) : BlueFalconPlugin {
    
    private val cache = PeripheralCache()
    
    /**
     * Configuration for the caching plugin
     */
    class Config : PluginConfig() {
        /**
         * Whether to cache service discovery results
         */
        var cacheServices: Boolean = true
        
        /**
         * Whether to cache characteristic discovery results
         */
        var cacheCharacteristics: Boolean = true
        
        /**
         * Duration to keep cache entries valid
         */
        var cacheDuration: Duration = 5.minutes
        
        /**
         * Whether to invalidate cache when peripheral disconnects
         */
        var invalidateOnDisconnect: Boolean = true
        
        /**
         * Maximum number of peripherals to cache
         */
        var maxCachedPeripherals: Int = 50
    }
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        // Plugin installed
    }
    
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        return call
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        if (result.isSuccess) {
            // Initialize cache entry for this peripheral
            cache.ensureEntry(call.peripheral.uuid, config.cacheDuration)
        } else if (config.invalidateOnDisconnect) {
            cache.invalidate(call.peripheral.uuid)
        }
    }
    
    override suspend fun onBeforeRead(call: ReadCall): ReadCall {
        // Check if we have cached characteristic data
        if (config.cacheCharacteristics) {
            val cached = cache.getCharacteristic(
                call.peripheral.uuid,
                call.characteristic.uuid.toString()
            )
            if (cached != null) {
                // Cache hit - could return cached data in a real implementation
            }
        }
        return call
    }
    
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        if (result.isSuccess && config.cacheCharacteristics) {
            // Cache the read value
            result.getOrNull()?.let { value ->
                cache.cacheCharacteristic(
                    call.peripheral.uuid,
                    call.characteristic.uuid.toString(),
                    value
                )
            }
        }
    }
    
    /**
     * Get cached services for a peripheral
     */
    fun getCachedServices(peripheralUuid: String): List<BluetoothService>? {
        return if (config.cacheServices) {
            cache.getServices(peripheralUuid)
        } else {
            null
        }
    }
    
    /**
     * Cache services for a peripheral
     */
    fun cacheServices(peripheralUuid: String, services: List<BluetoothService>) {
        if (config.cacheServices) {
            cache.cacheServices(peripheralUuid, services)
        }
    }
    
    /**
     * Invalidate cache for a specific peripheral
     */
    fun invalidatePeripheral(peripheralUuid: String) {
        cache.invalidate(peripheralUuid)
    }
    
    /**
     * Clear all cached data
     */
    fun clearCache() {
        cache.clear()
    }
    
    companion object {
        /**
         * Creates a new CachingPlugin instance with the given configuration
         */
        fun create(configure: Config.() -> Unit = {}): CachingPlugin {
            val config = Config().apply(configure)
            return CachingPlugin(config)
        }
    }
}

/**
 * Internal cache storage for peripheral data
 */
private class PeripheralCache {
    private val entries = mutableMapOf<String, CacheEntry>()
    private val timeSource = TimeSource.Monotonic
    
    private fun getOrCreate(peripheralUuid: String, ttl: Duration): CacheEntry {
        val existing = entries[peripheralUuid]
        if (existing != null && !existing.isExpired()) {
            return existing
        }
        
        val newEntry = CacheEntry(peripheralUuid, ttl, timeSource.markNow())
        entries[peripheralUuid] = newEntry
        return newEntry
    }
    
    fun ensureEntry(peripheralUuid: String, ttl: Duration) {
        getOrCreate(peripheralUuid, ttl)
    }
    
    fun getServices(peripheralUuid: String): List<BluetoothService>? {
        val entry = entries[peripheralUuid]
        return if (entry != null && !entry.isExpired()) {
            entry.services
        } else {
            null
        }
    }
    
    fun cacheServices(peripheralUuid: String, services: List<BluetoothService>) {
        entries[peripheralUuid]?.services = services
    }
    
    fun getCharacteristic(peripheralUuid: String, characteristicUuid: String): ByteArray? {
        val entry = entries[peripheralUuid]
        return if (entry != null && !entry.isExpired()) {
            entry.characteristics[characteristicUuid]
        } else {
            null
        }
    }
    
    fun cacheCharacteristic(peripheralUuid: String, characteristicUuid: String, value: ByteArray) {
        entries[peripheralUuid]?.characteristics?.put(characteristicUuid, value)
    }
    
    fun invalidate(peripheralUuid: String) {
        entries.remove(peripheralUuid)
    }
    
    fun clear() {
        entries.clear()
    }
    
    private class CacheEntry(
        val peripheralUuid: String,
        val ttl: Duration,
        val createdAt: TimeSource.Monotonic.ValueTimeMark
    ) {
        var services: List<BluetoothService>? = null
        val characteristics = mutableMapOf<String, ByteArray>()
        
        fun isExpired(): Boolean {
            return createdAt.elapsedNow() > ttl
        }
    }
}

/**
 * DSL function to install caching plugin
 */
fun installCaching(configure: CachingPlugin.Config.() -> Unit): CachingPlugin {
    return CachingPlugin.create(configure)
}

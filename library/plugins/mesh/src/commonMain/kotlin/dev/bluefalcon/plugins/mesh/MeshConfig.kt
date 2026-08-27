package dev.bluefalcon.plugins.mesh

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.plugin.PluginConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the [MeshNode].
 *
 * Provides tuning parameters for mesh relay behavior including hop limits,
 * deduplication cache sizing, and service UUIDs for mesh discovery.
 */
class MeshConfig : PluginConfig() {
    /**
     * Maximum number of hops a message can travel through the mesh.
     * Messages exceeding this hop count are dropped and not relayed further.
     *
     * Lower values reduce network traffic but limit mesh reach.
     * Higher values allow larger meshes but increase duplicate traffic.
     *
     * Default: 5 (suitable for small ad-hoc BLE meshes of ~10-20 nodes)
     */
    var maxHopCount: Int = 5

    /**
     * Maximum number of message IDs to keep in the deduplication cache.
     * When the cache is full, oldest entries are evicted (LRU).
     *
     * Larger caches prevent more duplicate processing but use more memory.
     * Size should be proportional to expected message throughput * [dedupTtl].
     *
     * Default: 256
     */
    var dedupCacheSize: Int = 256

    /**
     * Time-to-live for entries in the deduplication cache.
     * Entries older than this are eligible for eviction even before
     * [dedupCacheSize] is reached.
     *
     * Should be longer than the maximum expected time for a message to
     * traverse the mesh (hops * connection/notification latency).
     *
     * Default: 30 seconds
     */
    var dedupTtl: Duration = 30.seconds

    /**
     * UUID of the mesh relay GATT service.
     * Nodes discover each other by scanning/advertising this service UUID.
     *
     * Change this to create isolated mesh networks that don't relay to each other.
     */
    var meshServiceUuid: Uuid = DEFAULT_MESH_SERVICE_UUID

    /**
     * UUID of the mesh relay GATT characteristic used for writing mesh messages.
     * Must be included in the service identified by [meshServiceUuid].
     */
    var meshCharacteristicUuid: Uuid = DEFAULT_MESH_CHARACTERISTIC_UUID

    /**
     * Name to include in advertisements for this mesh node.
     * Useful for debugging and identifying nodes.
     *
     * If null, no local name is advertised.
     */
    var advertisedName: String? = null

    /**
     * Whether to automatically scan for and connect to neighbor mesh nodes.
     *
     * When true, the mesh node actively discovers and connects to other nodes
     * advertising the mesh service. When false, the node only accepts inbound
     * connections and must be connected to by other nodes.
     *
     * Default: true
     */
    var autoConnectToNeighbors: Boolean = true

    /**
     * Maximum number of simultaneous neighbor connections to maintain.
     * BLE typically supports 7-10 simultaneous connections on mobile platforms.
     *
     * Default: 6 (leaves headroom for other app connections)
     */
    var maxNeighborConnections: Int = 6

    companion object {
        /**
         * Default mesh service UUID.
         * Uses a randomly-generated v4 UUID to minimize collision with standard services.
         */
        val DEFAULT_MESH_SERVICE_UUID: Uuid = Uuid.parse("a8b7c6d5-e4f3-4a2b-8c1d-0e9f8a7b6c5d")

        /**
         * Default mesh characteristic UUID.
         */
        val DEFAULT_MESH_CHARACTERISTIC_UUID: Uuid = Uuid.parse("b9c8d7e6-f5a4-4b3c-9d2e-1f0a9b8c7d6e")
    }
}

/**
 * DSL function to create a [MeshConfig] with the given configuration block.
 */
fun meshConfig(configure: MeshConfig.() -> Unit = {}): MeshConfig {
    return MeshConfig().apply(configure)
}

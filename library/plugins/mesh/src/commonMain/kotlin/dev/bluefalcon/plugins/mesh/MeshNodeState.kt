package dev.bluefalcon.plugins.mesh

/**
 * Represents the operational state of a [MeshNode].
 */
enum class MeshNodeState {
    /**
     * The mesh node is initialized but not yet started.
     * Call [MeshNode.start] to begin mesh operations.
     */
    Idle,

    /**
     * The mesh node is actively running:
     * - Advertising as a peripheral for inbound connections
     * - Scanning and connecting to neighbor peripherals as a central
     * - Relaying messages between neighbors
     */
    Running,

    /**
     * The mesh node is in the process of stopping.
     * Connections are being closed and advertising is being stopped.
     */
    Stopping,

    /**
     * The mesh node has been stopped.
     * Call [MeshNode.start] to restart mesh operations.
     */
    Stopped
}

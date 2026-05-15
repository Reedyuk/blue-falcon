# ADR 0005: BLE Device Cloning Plugin

**Status:** Proposed

**Date:** 2026-05-15

**Deciders:** Andrew Reed, Community Contributors

**Technical Story:** Feature request to clone a BLE device — capturing advertisement data, services, characteristics, and descriptors into a portable snapshot that can be replayed or used for testing/simulation.

## Context

During development and testing of BLE applications, engineers frequently need to:

1. **Capture a device profile** for offline development when the physical hardware is unavailable.
2. **Reproduce issues** by sharing exact device snapshots with team members who don't have the hardware.
3. **Create test fixtures** that represent real-world device topologies (services, characteristics, descriptors, and their values).
4. **Audit devices** by taking full snapshots of advertisement payloads and GATT tables for analysis.

Currently, there is no built-in way to capture a full device profile (advertisement info + GATT table) through Blue Falcon. Developers resort to manual note-taking, platform-specific tools (nRF Connect, LightBlue), or custom one-off scripts.

With the plugin architecture from ADR 0002, we can provide this as an optional plugin that hooks into discovery, connection, and characteristic read events to build a complete device clone.

### What "Cloning" Means

A BLE device clone in this context is a **read-only snapshot** containing:

- **Advertisement data**: Device name, local name, manufacturer data, service UUIDs advertised, TX power level, RSSI at time of capture.
- **GATT services**: All discovered services with their UUIDs and properties.
- **GATT characteristics**: For each service — all characteristics with UUIDs, properties (read/write/notify/indicate flags), and optionally their current values.
- **GATT descriptors**: For each characteristic — all descriptors with UUIDs and values (e.g., CCCD, user description).
- **Metadata**: Peripheral identifier, timestamp of capture, platform captured on, MTU size.

This is **not** about spoofing or impersonating a BLE device on the radio layer — it is a data capture for development, testing, and documentation purposes.

## Decision

We will implement a BLE device cloning plugin (`blue-falcon-plugin-clone`) that captures a complete snapshot of a connected BLE peripheral's advertisement data and GATT table.

The plugin will:

1. **Follow the existing plugin pattern** established by ADR 0002 (interceptor hooks, plugin lifecycle).
2. **Capture advertisement data** during scan/discovery by hooking into peripheral discovery events.
3. **Capture the full GATT table** after connection and service/characteristic discovery completes, by reading all readable characteristics and descriptors.
4. **Produce a serializable `DeviceClone` data model** that can be exported to JSON and re-imported.
5. **Support selective cloning** — users can choose to clone only advertisement data, only GATT structure, or a full deep clone (including reading all characteristic/descriptor values).
6. **Provide a `CloneCallback` interface** for progress reporting (useful when reading many characteristics sequentially).
7. **Operate cross-platform** using only Blue Falcon's core BLE operations (scan, connect, discoverServices, readCharacteristic, readDescriptor).

### Data Model

```kotlin
@Serializable
data class DeviceClone(
    val peripheralId: String,
    val peripheralName: String?,
    val capturedAt: String, // ISO-8601 timestamp
    val platform: String,
    val rssi: Float?,
    val mtuSize: Int?,
    val advertisement: AdvertisementClone,
    val services: List<ServiceClone>
)

@Serializable
data class AdvertisementClone(
    val localName: String?,
    val manufacturerData: ByteArray?,
    val serviceUuids: List<String>,
    val txPowerLevel: Int?
)

@Serializable
data class ServiceClone(
    val uuid: String,
    val name: String?,
    val characteristics: List<CharacteristicClone>
)

@Serializable
data class CharacteristicClone(
    val uuid: String,
    val name: String?,
    val properties: List<String>, // e.g. ["read", "write", "notify"]
    val value: ByteArray?,
    val descriptors: List<DescriptorClone>
)

@Serializable
data class DescriptorClone(
    val uuid: String,
    val value: ByteArray?
)
```

### Plugin API

```kotlin
class DeviceClonePlugin(
    private val config: CloneConfig = CloneConfig()
) : BlueFalconPlugin {

    suspend fun cloneDevice(peripheral: BluetoothPeripheral): DeviceClone

    fun exportToJson(clone: DeviceClone): String
    fun importFromJson(json: String): DeviceClone
}

data class CloneConfig(
    val readCharacteristicValues: Boolean = true,
    val readDescriptorValues: Boolean = true,
    val includeAdvertisementData: Boolean = true,
    val readTimeoutMs: Long = 5000,
    val callback: CloneCallback? = null
)

interface CloneCallback {
    fun onCloneProgress(current: Int, total: Int, message: String)
    fun onCloneComplete(clone: DeviceClone)
    fun onCloneError(error: Throwable)
}
```

## Consequences

### Positive

- Developers can capture real device profiles for offline development and testing without external tools.
- Snapshots can be shared as JSON files across teams, enabling reproducible testing without hardware.
- Cross-platform — works on all platforms Blue Falcon supports since it uses standard BLE operations.
- Plugin architecture keeps this optional; users who don't need cloning don't pay the dependency cost.
- Can serve as the foundation for future mock/simulation plugins that replay cloned device data.

### Negative

- Reading all characteristics and descriptors can be slow on devices with large GATT tables (mitigated by progress callbacks and selective cloning config).
- Some characteristics may require authentication/pairing to read, which the clone will record as `null` values.
- Advertisement manufacturer data format varies by vendor — the plugin captures raw bytes without interpretation.
- Characteristic properties (read/write/notify flags) require platform-specific extraction that may need `expect/actual` helpers.

### Neutral

- Published as a separate artifact: `dev.bluefalcon:blue-falcon-plugin-clone`
- Follows the same version scheme as other plugins
- JSON serialization uses `kotlinx.serialization` (already a common Kotlin Multiplatform dependency)
- The clone is a point-in-time snapshot; dynamic values will reflect whatever was read at capture time

## Alternatives Considered

### Alternative 1: Built-in Core Feature

Add cloning directly to the core `BlueFalcon` class.

**Pros:**
- No additional dependency for users
- Direct access to internal state

**Cons:**
- Bloats core library
- Violates plugin architecture (ADR 0002)
- Adds `kotlinx.serialization` as a core dependency

**Why not chosen:** The plugin architecture exists precisely for optional features like this.

### Alternative 2: Export-Only (No Data Model)

Simply dump raw platform-specific GATT data to a log or text format.

**Pros:**
- Simpler implementation
- No serialization dependency

**Cons:**
- Not machine-readable or importable
- Platform-specific output formats
- Cannot be used programmatically for testing/simulation

**Why not chosen:** A structured, serializable data model is essential for the use cases (testing, sharing, future simulation).

### Alternative 3: Platform-Specific Clone Tools

Recommend users use nRF Connect, LightBlue, or similar platform tools.

**Pros:**
- No development effort
- Mature, tested tools

**Cons:**
- Not integrated into the Blue Falcon workflow
- Output formats are tool-specific and not easily consumed programmatically
- Doesn't work from within the application being developed

**Why not chosen:** Integration with Blue Falcon's plugin system enables programmatic use within applications, CI pipelines, and test frameworks.

## Implementation Notes

- The plugin module will live at `library/plugins/clone/`
- Uses `kotlinx.serialization` for JSON import/export
- The `cloneDevice()` function requires the peripheral to be connected with services discovered
- Advertisement data capture hooks into `onPeripheralDiscovered` plugin event to cache scan results
- Deep clone reads each characteristic sequentially to avoid BLE stack congestion
- Platform-specific characteristic property flags will need an `expect/actual` helper or an extension on `BluetoothCharacteristic`
- Future enhancement: a companion "mock device" plugin that uses `DeviceClone` data to simulate a peripheral for unit tests

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)
- [ADR 0004: Expose Characteristic Notification Events to Consumers and Plugins](0004-expose-characteristic-notification-events.md)

## References

- [Bluetooth GATT Specification](https://www.bluetooth.com/specifications/gatt/)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
- [Blue Falcon Plugin System](https://github.com/Reedyuk/blue-falcon)

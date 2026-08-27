# ADR 0010: RSSI/Proximity Plugin

**Status:** Proposed

**Date:** 2026-08-27

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** `BlueFalconEngine.rssiUpdates` emits raw, noisy signal-strength samples with
no smoothing or distance estimation, forcing every beacon/proximity-style consumer to reimplement
the same filtering logic.

## Context

Blue Falcon already exposes:

```kotlin
val rssiUpdates: SharedFlow<Pair<String, Float>>  // (peripheral uuid, rssi)
```

on both `BlueFalconEngine` and `BlueFalcon`. This is a direct pass-through of whatever RSSI sample
the platform last reported (on a scan result, a connection-time RSSI read, or periodic re-read,
depending on engine). Raw BLE RSSI is notoriously noisy — indoor multipath, body shadowing, and
antenna orientation can swing a stationary device's reported RSSI by 10+ dB sample-to-sample.
Every consumer that wants a stable "near/far" signal, a smoothed proximity indicator, or a rough
distance estimate for a beacon-style use case (asset tracking, proximity unlock, find-my-device)
currently has to write their own smoothing filter on top of `rssiUpdates` from scratch, since
Blue Falcon only forwards the raw sample.

This is a broadly-applicable, self-contained piece of signal processing that doesn't require any
engine changes — every engine already emits `rssiUpdates` — making it a clean candidate for a
plugin, consistent with ADR 0002's principle of keeping `core` minimal and delivering optional
behavior through plugins.

## Decision

We will add a `blue-falcon-plugin-proximity` module that subscribes to the existing
`BlueFalcon.rssiUpdates` flow, applies a configurable smoothing filter per peripheral, and
exposes a stable, continuously-updated proximity reading — with an optional rough distance
estimate via the standard log-distance path-loss model. No `BlueFalconEngine` changes are
required.

### New types (`plugins/proximity`)

```kotlin
enum class ProximityZone { Immediate, Near, Far, Unknown }

data class ProximityReading(
    val peripheralUuid: String,
    val rawRssi: Float,
    val smoothedRssi: Float,
    /** Only populated when Config.txPower (or the peripheral's advertised TX power) is known. */
    val estimatedDistanceMeters: Double?,
    val zone: ProximityZone,
    val sampleCount: Int,
)

class ProximityPlugin(private val config: Config) : BlueFalconPlugin {
    class Config : PluginConfig() {
        /** Smoothing strategy applied to each peripheral's raw RSSI samples independently. */
        var smoothing: SmoothingStrategy = SmoothingStrategy.Kalman()
        /** Reference RSSI at 1 meter, used for distance estimation when a peripheral doesn't advertise its own. */
        var defaultTxPower: Float = -59f
        /** Path-loss exponent; 2.0 = free space, higher for indoor/obstructed environments. */
        var pathLossExponent: Double = 2.0
        /** Thresholds (in dBm, smoothed) for Immediate/Near/Far classification. */
        var immediateThreshold: Float = -50f
        var nearThreshold: Float = -75f
    }

    /** Latest smoothed reading per peripheral, keyed by uuid. Replays the current value to new collectors. */
    val proximityReadings: StateFlow<Map<String, ProximityReading>>

    fun readingFor(peripheral: BluetoothPeripheral): ProximityReading?
}

sealed class SmoothingStrategy {
    /** Exponential moving average: smoothed = alpha * raw + (1 - alpha) * previous. */
    data class MovingAverage(val alpha: Double = 0.2) : SmoothingStrategy()
    /** 1D Kalman filter tuned for RSSI's process/measurement noise characteristics. */
    data class Kalman(
        val processNoise: Double = 0.008,
        val measurementNoise: Double = 4.0,
    ) : SmoothingStrategy()
    /** No smoothing; smoothedRssi == rawRssi. Useful for testing/comparison. */
    object None : SmoothingStrategy()
}
```

`smoothedRssi` is computed statelessly-per-peripheral (one filter instance per uuid, created
lazily on first sample) so that a noisy peripheral cannot affect another's readings. Distance
estimation uses the standard formula `distance = 10 ^ ((txPower - smoothedRssi) / (10 * n))` where
`n` is `pathLossExponent`; `estimatedDistanceMeters` is `null` until at least a configurable
minimum number of samples have been observed, to avoid reporting a wild first-sample estimate.

## Consequences

### Positive

- Removes the most commonly duplicated piece of BLE proximity logic from every consuming app.
- `StateFlow` (not `SharedFlow`) means a UI that binds late still sees the current reading
  immediately, consistent with the `StateFlow`-based approach taken in ADR 0008.
- Kalman and moving-average strategies are both offered since they suit different use cases
  (Kalman: better steady-state accuracy; moving average: simpler, cheaper, more predictable
  lag) — apps pick per their needs rather than Blue Falcon guessing.
- Zero engine changes — works identically across every existing platform immediately.
- Purely additive; doesn't touch `rssiUpdates` or any other existing API.

### Negative

- Distance estimates are inherently approximate (BLE RSSI-to-distance is well known to be
  unreliable beyond rough proximity classification); the plugin's docs must be explicit that
  `estimatedDistanceMeters` is a rough estimate, not a precise measurement, to set correct
  expectations.
- Per-peripheral filter state grows unboundedly for apps that scan many transient peripherals
  without ever calling `clearPeripherals()`; the plugin will prune entries when a peripheral is
  removed from `BlueFalcon.peripherals` to bound memory (see Implementation Notes).
- Adds one more plugin module for maintainers to keep in sync with core API changes, though the
  surface it depends on (`rssiUpdates`, `peripherals`) is already stable.

### Neutral

- New Gradle module `blue-falcon-plugin-proximity`, following the existing per-plugin
  publishing pattern.

## Alternatives Considered

### Alternative 1: Add smoothing directly to `rssiUpdates` in core

Have `BlueFalconEngine`/`BlueFalcon` emit already-smoothed RSSI instead of raw values.

**Pros:**
- No new module; smoothing "just works" for everyone.

**Cons:**
- Removes access to the raw sample for consumers who want it (e.g. apps doing their own
  specialized filtering).
- Bakes a specific smoothing algorithm and its tunable parameters into core for a need only some
  consumers have, again contrary to ADR 0002's core-stays-minimal principle.

**Why not chosen:** Raw `rssiUpdates` remains valuable as-is; smoothing is opt-in additional
value, not a replacement.

### Alternative 2: Ship only a moving average, skip Kalman

Simpler implementation, less configuration surface.

**Pros:**
- Less code, easier to reason about and test.

**Cons:**
- Kalman filtering is meaningfully better at rejecting the kind of measurement noise BLE RSSI
  exhibits, and is already the filter of choice in several well-known BLE proximity
  implementations (e.g. Android's own beacon libraries).

**Why not chosen:** Both are simple enough to implement and test; offering the choice costs little
and directly serves the "continuous ranging with smoothing (Kalman/moving average)" requirement.

## Implementation Notes

- New module: `library/plugins/proximity/` (`blue-falcon-plugin-proximity`).
- Subscribes to `BlueFalcon.rssiUpdates` and `BlueFalcon.peripherals` in `install()`; on each
  `peripherals` emission, prunes `proximityReadings` entries whose uuid is no longer present, to
  bound memory growth from transient scan results.
- If a peripheral advertises its own TX power (already parsed by engines that support extended
  advertising data), the plugin will prefer that over `Config.defaultTxPower` when computing
  `estimatedDistanceMeters`.
- Tested purely via `FakeBlueFalconEngine`'s existing `rssiUpdates`/`peripherals` emission
  support — no new test infrastructure required, following the same approach used for the retry
  plugin and ADR 0008's state machine tests.

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)

## References

- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/BlueFalconEngine.kt` (`rssiUpdates`)
- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/BlueFalcon.kt`

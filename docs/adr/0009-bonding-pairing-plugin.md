# ADR 0009: Bonding/Pairing Plugin

**Status:** Proposed

**Date:** 2026-08-27

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** `BlueFalconEngine.createBond`/`removeBond` exist but behave inconsistently
across platforms, with no observable bond state and no way to know a bond attempt has finished
before touching encrypted characteristics.

## Context

`BlueFalcon`/`BlueFalconEngine` already expose `createBond(peripheral)` and
`removeBond(peripheral)`, plus an unused `BlueFalconBondState` enum (`None`, `Bonding`, `Bonded`)
that no engine currently populates or emits. Auditing every engine implementation shows just how
differently "bonding" behaves per platform:

| Engine | `createBond` | `removeBond` |
|---|---|---|
| Android | Calls `BluetoothDevice.createBond()`; result arrives asynchronously via `ACTION_BOND_STATE_CHANGED`, observed internally by a registered broadcast receiver but never surfaced to the caller. | Calls the hidden `removeBond()` method via reflection (not part of the public Android SDK). |
| Apple (iOS/macOS) | No-op — CoreBluetooth has no explicit pairing API; pairing happens implicitly the first time an encrypted characteristic is accessed. | No-op — must be done through the system Bluetooth Settings/System Preferences UI; apps cannot unpair programmatically. |
| Windows | Throws `UnsupportedOperationException`. | Throws `UnsupportedOperationException`. |
| JS (Web Bluetooth) | Throws `UnsupportedOperationException` — the Web Bluetooth spec has no pairing API. | Throws `UnsupportedOperationException`. |
| Raspberry Pi (BlueZ) | Calls the BlueZ pairing API on an already-connected peripheral; throws if not connected. | Throws `UnsupportedOperationException`. |

Consequences for consumers today:

- There is no way to `await` a bond attempt — `createBond()` on Android returns as soon as the
  platform call is issued, not when bonding actually completes or fails.
- There is no cross-platform signal for "is this peripheral currently bonded", so apps cannot
  decide whether to attempt an encrypted read/write versus first requesting a bond.
- Every engine's differing behavior (no-op vs. throw vs. async) is invisible until an app hits it
  at runtime on a specific platform.

Bonding is inherently one of the least portable parts of BLE — the correct response to "please
unbond this device" genuinely differs (Android: system call; Apple: point the user at Settings;
Windows/JS: unsupported). Blue Falcon should not hide that unevenness behind a false promise of
uniform behavior, but it should give consumers a single, honest, observable API instead of having
to special-case `createBond`/`removeBond`'s current inconsistent throw/no-op/async behavior
themselves.

## Decision

We will add a `blue-falcon-plugin-bonding` module that layers a typed, observable bonding
workflow on top of the existing `createBond`/`removeBond` engine calls, without changing the
`BlueFalconEngine` interface. Where a platform can report an authoritative bond state change
(Android today; others as their platform APIs allow), the plugin surfaces it reactively. Where a
platform cannot (Apple, Windows, JS), the plugin reports that capability gap explicitly rather
than hanging or silently no-op-ing.

### New types (`plugins/bonding`)

```kotlin
enum class BondCapability {
    /** The platform can both request bonding and report state changes (Android, RPi/BlueZ). */
    Supported,
    /** Bonding happens implicitly on first encrypted access; the app cannot request or observe it (Apple). */
    Implicit,
    /** The platform exposes no bonding API at all (Windows, JS/Web Bluetooth). */
    Unsupported,
}

data class BondState(
    val peripheralUuid: String,
    val state: BlueFalconBondState,     // reuse the existing None/Bonding/Bonded enum
    val capability: BondCapability,
)

sealed class BondResult {
    data class Bonded(val peripheralUuid: String) : BondResult()
    data class Failed(val cause: Throwable) : BondResult()
    object Unsupported : BondResult()
    object TimedOut : BondResult()
}

class BondingPlugin(private val config: Config) : BlueFalconPlugin {
    class Config : PluginConfig() {
        var bondTimeout: Duration = 30.seconds
    }

    /** Current bond state for every peripheral this plugin has observed, keyed by uuid. */
    val bondStates: StateFlow<Map<String, BondState>>

    /** Requests a bond and suspends until it resolves, times out, or the platform can't support it. */
    suspend fun requestBond(peripheral: BluetoothPeripheral): BondResult

    /** Requests removal; on platforms where this isn't programmatically possible, returns Unsupported immediately. */
    suspend fun requestUnbond(peripheral: BluetoothPeripheral): BondResult
}
```

`requestBond` calls `blueFalcon.createBond(peripheral)` and then:
- on Android, awaits the plugin's internal collection of a (new, additive)
  `BlueFalconEngine.bondStateUpdates: SharedFlow<BondStateUpdate>` that the Android engine will be
  extended to emit from its existing (currently internal-only) `ACTION_BOND_STATE_CHANGED`
  receiver, resolving `Bonded`/`Failed` accordingly, or `TimedOut` after `bondTimeout`;
- on RPi, similarly awaits BlueZ's pairing completion signal;
- on platforms with `BondCapability.Unsupported`/`Implicit` (Apple, Windows, JS), returns
  `BondResult.Unsupported` immediately without calling into the engine, since the engine's current
  no-op/throw behavior gives the plugin nothing to await.

`requestUnbond` mirrors this, immediately returning `Unsupported` on Apple/Windows/JS to match
their documented lack of a programmatic API.

## Consequences

### Positive

- One typed, awaitable API (`requestBond`/`requestUnbond`) replaces silently inconsistent
  fire-and-forget/no-op/throw behavior across five engines.
- `bondStates: StateFlow<...>` gives apps a reactive signal to gate encrypted GATT operations on,
  instead of guessing when an async Android bond has actually completed.
- `BondCapability` makes platform limitations a first-class, checkable value instead of a runtime
  surprise (`UnsupportedOperationException` mid-flow).
- Additive: existing `BlueFalcon.createBond`/`removeBond` callers are unaffected; the plugin is
  opt-in.

### Negative

- Requires one small additive change to `BlueFalconEngine`/`AndroidEngine`/RPi engine
  (`bondStateUpdates` flow) to let the plugin observe real completion instead of guessing from a
  timeout alone. This is the one piece of this ADR that isn't purely additive-at-the-plugin-layer.
- Cannot make Apple/Windows/JS behave uniformly — the ADR is explicit that `Unsupported`/
  `Implicit` results are permanent for those platforms, which may surprise apps expecting parity.
- `bondTimeout`-based fallback on platforms without a real completion signal (if a future platform
  is added without one) means `Bonded` can never be positively confirmed there — only inferred by
  the absence of a failure within the timeout window, which the plugin will document as a caveat
  rather than backfill artificial confidence.

### Neutral

- New Gradle module `blue-falcon-plugin-bonding`, following the existing per-plugin publishing
  pattern (`blue-falcon-plugin-retry`, `-caching`, `-logging`, etc.).

## Alternatives Considered

### Alternative 1: Extend `BlueFalconBondState`/`createBond`/`removeBond` directly in core

Make `createBond` suspend until resolved and add `bondStateUpdates` directly to
`BlueFalconEngine`/`BlueFalcon`, without a separate plugin.

**Pros:**
- One fewer artifact/dependency for consumers who always want this.

**Cons:**
- Bonding is a niche need (most BLE apps never bond); forcing every engine to implement awaitable
  semantics inflates core for consumers who don't need it.
- Breaks the project's established pattern (ADR 0002) of keeping `core` minimal and delivering
  optional behavior through plugins.

**Why not chosen:** Consistent with how retry, caching, logging, FOTA, clone, and broadcast are
already delivered as opt-in plugins rather than baked into core.

### Alternative 2: Fully unify behavior across platforms (e.g., simulate bonding on Apple)

Attempt to paper over Apple's lack of an explicit pairing API by triggering an encrypted
characteristic read as a synthetic "bond request".

**Pros:**
- Uniform-looking API surface.

**Cons:**
- Requires guessing at an encrypted characteristic to touch, which may not exist on every
  peripheral, and conflates "I want a raw pairing" with "I want to read this specific value".
- Silently masks a genuine, permanent platform difference that apps need to design around.

**Why not chosen:** Honesty about `BondCapability` is more useful to consumers than a leaky
simulation.

## Implementation Notes

- New module: `library/plugins/bonding/` (`blue-falcon-plugin-bonding`), following the existing
  plugin Gradle template (see `library/plugins/retry/build.gradle.kts`).
- Additive engine change: `BlueFalconEngine.bondStateUpdates: SharedFlow<BondStateUpdate>`
  (default empty-emitting for engines that don't support it), populated first on
  `AndroidEngine` (wiring its existing `ACTION_BOND_STATE_CHANGED` receiver, which today only
  updates internal book-keeping, out to this flow) and RPi's BlueZ pairing completion signal.
- `BondCapability` is a static, per-engine constant Blue Falcon can expose via
  `BlueFalconEngine.centralCapabilities` (already an existing per-engine capability descriptor
  used for characteristic-write capabilities) rather than inventing a second capability channel.

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)
- [ADR 0008: Structured Per-Peripheral Connection State Machine](0008-structured-peripheral-connection-state-machine.md)

## References

- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/BlueFalcon.kt` (`createBond`/`removeBond`)
- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/BluetoothStates.kt` (`BlueFalconBondState`)
- `library/engines/android/src/androidMain/kotlin/dev/bluefalcon/engine/android/AndroidEngine.kt`
- `library/engines/apple-shared/src/appleMain/kotlin/dev/bluefalcon/engine/apple/AppleEngine.kt`

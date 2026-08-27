# ADR 0008: Structured Per-Peripheral Connection State Machine

**Status:** Proposed

**Date:** 2026-08-27

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** Consumers must stitch together `connectionStateUpdates`, `serviceDiscoveryUpdates`,
and a racy `connectionState()` poll to know when a peripheral is actually usable, and have no
typed information about *why* a peripheral disconnected.

## Context

Blue Falcon's core (`BlueFalcon`/`BlueFalconEngine`) already exposes low-level, reactive
connection primitives:

- `BluetoothPeripheralState` — a flat enum (`Connecting`, `Connected`, `Disconnected`,
  `Disconnecting`, `Unknown`).
- `connectionStateUpdates: SharedFlow<ConnectionStateUpdate>` — emits transitions, but as a
  `SharedFlow` with no replay: a collector that subscribes after a peripheral has already
  connected sees nothing until the *next* transition.
- `connectionState(peripheral): BluetoothPeripheralState` — a synchronous poll. Its own KDoc
  warns it is racy immediately after calling `connect()`, because the platform connect callback
  is asynchronous.
- `serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate>` — a **separate** flow reporting
  `ServicesDiscovered` / `CharacteristicsDiscovered` phases, decoupled from connection state.

To know "is this peripheral connected *and* ready to have characteristics read/written",
consumers must manually combine two independent flows, track per-peripheral bookkeeping
themselves, and re-derive it from scratch in every app. There is also no typed signal for *why*
a peripheral disconnected (user-initiated vs. a failed connect attempt vs. an unexpected drop),
which apps need to decide whether to reconnect, and which would let a future retry policy (e.g.
`blue-falcon-plugin-retry`) make smarter decisions.

## Decision

We will introduce a typed, per-peripheral connection state machine, derived entirely inside
`BlueFalcon` (the core orchestration layer) by folding the *existing* `connectionStateUpdates`
and `serviceDiscoveryUpdates` engine flows plus the outcome of `connect()`/`disconnect()` calls.
No `BlueFalconEngine` or platform implementation changes are required — this is purely an
additive layer on top of signals engines already emit.

### New types (`core/PeripheralConnectionState.kt`)

```kotlin
sealed class PeripheralConnectionState {
    data class Disconnected(val reason: DisconnectReason? = null) : PeripheralConnectionState()
    object Connecting : PeripheralConnectionState()
    object Connected : PeripheralConnectionState()   // connected, GATT service table not yet populated
    object Ready : PeripheralConnectionState()        // connected AND services discovered
    object Disconnecting : PeripheralConnectionState()
}

sealed class DisconnectReason {
    object UserInitiated : DisconnectReason()   // disconnect() was called by the app
    data class ConnectFailed(val cause: Throwable) : DisconnectReason() // connect() itself failed
    object Unexpected : DisconnectReason()      // dropped while Connected/Ready, cause unknown to core
}
```

`Ready` reflects only that the GATT **service** table is populated (the
`ServiceDiscoveryPhase.ServicesDiscovered` event), not that every characteristic for every
service has been discovered — consumers choose which services they care about and call
`discoverCharacteristics` themselves, exactly as they do today via `serviceDiscoveryUpdates`.
Claiming a fuller "everything discovered" state would require guessing consumer intent, so we
deliberately keep `Ready`'s contract narrow and honest. A `Discovering` state was considered and
rejected — see Alternatives.

### New `BlueFalcon` API

```kotlin
val connectionStates: StateFlow<Map<String, PeripheralConnectionState>>   // keyed by peripheral.uuid

fun peripheralState(peripheral: BluetoothPeripheral): PeripheralConnectionState // synchronous, current value

fun connectionStateFlow(peripheral: BluetoothPeripheral): StateFlow<PeripheralConnectionState>
```

`connectionStateFlow` is a `StateFlow` (not `SharedFlow`): new collectors immediately observe the
current state, eliminating the "subscribed after the event fired" race that `connectionState()`'s
KDoc already warns about. It is derived via `.map { }.stateIn(engine.scope, Eagerly, ...)` over
the shared `connectionStates` map — no manual locking, consistent with how the rest of the
codebase (`characteristicWriteCapabilities`, `peripherals`) already exposes `StateFlow`s.

### State derivation rules

- `connect()` sets `Connecting` immediately; if the underlying `engine.connect()` call throws
  synchronously (surfaced today as `Result.failure` from `interceptConnect`), state becomes
  `Disconnected(ConnectFailed(cause))` right away without waiting for an engine event.
- The real `Connected` / `Disconnected` transitions still come from `connectionStateUpdates`,
  since — as the existing docs note — a successful `engine.connect()` call only means the
  request was issued, not that the link is up.
- `disconnect()` sets `Disconnecting` before invoking `engine.disconnect()`.
- When a `Disconnected` event arrives from `connectionStateUpdates`, the **previous** state
  decides the reason: previous `Disconnecting` → `UserInitiated`; previous `Connecting` →
  `ConnectFailed` (belt-and-braces for engines that signal async connect failures this way
  instead of throwing); previous `Connected`/`Ready` → `Unexpected`.
- A `ServiceDiscoveryUpdate(phase = ServicesDiscovered)` event flips `Connected` → `Ready`.
  `CharacteristicsDiscovered` events do not change state (see above).
- Existing `connectionStateUpdates`, `serviceDiscoveryUpdates`, and `connectionState()` are
  untouched — this is purely additive.

## Consequences

### Positive

- Single, typed source of truth for "is this peripheral usable right now", replacing manual
  flow-stitching in every consuming app.
- `StateFlow` semantics remove the subscribe-after-the-fact race that today's docs merely warn
  about instead of solving.
- Typed `DisconnectReason` gives apps (and future plugins, e.g. retry policies) a basis for
  deciding whether to reconnect.
- Zero engine/platform changes — works identically on every existing `BlueFalconEngine`
  implementation (Android, Apple, JS, Windows, RPi) the moment it's merged into core.
- Fully additive/non-breaking: existing `connectionState()`, `connectionStateUpdates`, and
  `serviceDiscoveryUpdates` APIs are unchanged.

### Negative

- `Unexpected` disconnects carry no platform error code/cause in this iteration, since
  `ConnectionStateUpdate` doesn't carry one today. A follow-up ADR could extend
  `ConnectionStateUpdate` with an optional cause per engine to enrich this further.
- `Ready` only guarantees service-level discovery, not characteristics — consumers who assumed a
  hypothetical fuller state must still branch on `serviceDiscoveryUpdates` for characteristics,
  same as today.
- One more piece of state maintained per peripheral for the lifetime of the `BlueFalcon`
  instance (small memory cost, cleared via `clearPeripherals()`/explicit removal — see
  Implementation Notes).

### Neutral

- Adds one new source file and modest additions to `BlueFalcon.kt`; no new Gradle module or
  plugin required since this lives in `core`.

## Alternatives Considered

### Alternative 1: Add a `Discovering` intermediate state

Model the time between `Connected` and `Ready` as an explicit `Discovering` state.

**Pros:**
- Slightly more granular for UI spinners.

**Cons:**
- There is no distinct engine signal for "discovery started" separate from `Connected` itself
  (auto-discovery is simply triggered internally by engines immediately after connecting) — the
  state would be inferred purely from elapsed time, not a real event, which is misleading.

**Why not chosen:** Would fabricate precision the underlying signals don't actually provide.

### Alternative 2: Change `connectionState()`'s return type in place

Widen the existing `connectionState(peripheral): BluetoothPeripheralState` to return the new
sealed state directly instead of adding new API surface.

**Pros:**
- One connection-state API instead of two.

**Cons:**
- Breaking change for every existing consumer and platform sample pattern-matching on
  `BluetoothPeripheralState`.

**Why not chosen:** Violates the additive/non-breaking approach used by every prior ADR in this
series (e.g. ADR 0004's notification events were added alongside existing delegates, not in
place of them).

### Alternative 3: Implement per-engine instead of in core

Have each `BlueFalconEngine` (Android, Apple, JS, Windows, RPi) compute and expose its own
`PeripheralConnectionState`.

**Pros:**
- Engines could enrich `Unexpected`/`ConnectFailed` with real platform error codes immediately.

**Cons:**
- Six independent implementations to write, test, and keep consistent instead of one.
- Most of the derivation logic (folding two flows) is platform-agnostic and belongs in core.

**Why not chosen:** Core-only implementation delivers the same value today with a fraction of the
surface area; richer per-platform error causes can be layered in later without breaking this API
(`ConnectFailed`/`Unexpected` are already open to carrying more data).

## Implementation Notes

- New file: `library/core/src/commonMain/kotlin/dev/bluefalcon/core/PeripheralConnectionState.kt`.
- `BlueFalcon.kt` gains a `MutableStateFlow<Map<String, PeripheralConnectionState>>`, two
  collectors launched in `init {}` (one per existing engine flow), and state transitions wired
  into `connect()`/`disconnect()`.
- No changes to `BlueFalconEngine`, `PluginRegistry`, or any platform engine implementation.
- Tested via `FakeBlueFalconEngine`'s existing `emitConnectionStateUpdate` /
  `emitServiceDiscoveryUpdate` test helpers — no new test infrastructure required.
- Entries are not proactively evicted from the map when a peripheral disconnects (so
  `Disconnected(reason)` remains inspectable); a future pass could add cleanup on
  `clearPeripherals()` if memory growth across many transient peripherals becomes a concern.

## Related Decisions

- [ADR 0004: Expose Characteristic Notification Events to Consumers and Plugins](0004-expose-characteristic-notification-events.md)

## References

- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/BluetoothStates.kt`
- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/BlueFalcon.kt`

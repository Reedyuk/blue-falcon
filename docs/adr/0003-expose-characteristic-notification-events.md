# ADR 0003: Expose Characteristic Notification Events to Consumers and Plugins

**Status:** Accepted

**Date:** 2026-04-18

**Deciders:** Blue Falcon maintainers

**Technical Story:** Issue: "Expose subscribed characteristic notifications to consumers (Flow / plugin hook / delegate)"

## Context

Blue Falcon 3.0 engines received native notification callbacks from BLE stacks, but payloads were not propagated through the 3.0 API surface. This prevented applications and plugins from reliably consuming notification streams for protocols built on indications/notifications.

The legacy wrapper also lacked wiring from engine callback events to `BlueFalconDelegate.didCharacteristcValueChanged`, so only explicit reads triggered that callback.

## Decision

We will expose characteristic notification payloads through two coordinated mechanisms:

1. Add `notifications: SharedFlow<ByteArray>` to `BluetoothCharacteristic`.
2. Add engine-level notification events (`CharacteristicNotification`) and dispatch them from core `BlueFalcon` to plugins via `BlueFalconPlugin.onNotificationReceived(peripheral, characteristic, value)`.

Engine native callback paths now emit both:
- the per-characteristic flow payload, and
- the engine notification event consumed by plugin dispatch and legacy delegate bridging.

## Consequences

### Positive

- Notification payloads are now observable reactively without polling mutable characteristic state.
- Plugins can process notifications consistently through a first-class callback.
- Legacy delegate callback `didCharacteristcValueChanged` can now be driven by actual subscribed notifications.

### Negative

- Engine interface and characteristic contracts expanded, requiring updates across all engine implementations.
- Additional flow buffers introduce minor memory overhead.

### Neutral

- Existing read/write/scan interceptor plugin behavior is unchanged.
- Notification events remain best-effort asynchronous signals (ordering and delivery still depend on platform BLE behavior).

## Alternatives Considered

### Alternative 1: Keep polling `characteristic.value`

**Pros:**
- No API changes.

**Cons:**
- Loses edge-trigger semantics.
- Can drop packets and race with rapid updates.

**Why not chosen:** Does not provide reliable notification-stream consumption.

### Alternative 2: Plugin-only notification hook without characteristic flow

**Pros:**
- Smaller surface area than adding both APIs.

**Cons:**
- Consumers not using plugins still lack a direct flow API.
- Harder to compose with coroutine/Flow pipelines at characteristic level.

**Why not chosen:** We need both app-level reactive consumption and plugin extensibility.

## Implementation Notes

- Android and Apple engines emit notification events from native characteristic-changed/value-updated callbacks.
- Legacy Android/JVM wrappers subscribe to engine notification events and invoke `didCharacteristcValueChanged`.

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)

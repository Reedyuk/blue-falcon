# ADR 0006: BLE Device Broadcast Plugin

**Status:** Accepted

**Date:** 2026-05-15

**Deciders:** Andrew Reed

**Technical Story:** After ADR 0005 introduced device cloning (capturing a remote peripheral's GATT tree and advertisement data), a natural extension is to replay that captured profile — advertising as the cloned device and serving its characteristic values to connecting centrals.

## Context

The clone plugin (ADR 0005) produces a `DeviceClone` value containing the full GATT service tree, characteristic values, descriptor values, advertisement metadata (local name, service UUIDs, manufacturer data), and peripheral metadata (RSSI, MTU, platform).

Currently Blue Falcon has no peripheral/server-role BLE capability. All engines implement only the central (client) role via `BlueFalconEngine`. Adding broadcast capability requires:

1. A **platform-agnostic interface** (`BluetoothAdvertiser`) that abstracts peripheral-role advertising and a local GATT server.
2. **Platform implementations** on Android (using `BluetoothLeAdvertiser` + `BluetoothGattServer`) and iOS/macOS (using `CBPeripheralManager`).
3. A **broadcast plugin** (`blue-falcon-plugin-broadcast`) that converts a `DeviceClone` into an `AdvertiseConfig` and drives the `BluetoothAdvertiser`.

### Platform constraints

| Platform | Advertising | GATT server | Manufacturer data | MAC spoof |
|----------|-------------|-------------|-------------------|-----------|
| Android API 21+ | ✅ `BluetoothLeAdvertiser` | ✅ `BluetoothGattServer` | ✅ | ❌ (randomised ≥ API 23) |
| iOS (foreground) | ⚠️ `CBPeripheralManager` — only local name + service UUIDs allowed | ✅ | ❌ | ❌ |
| iOS (background) | ⚠️ service UUIDs only | ✅ | ❌ | ❌ |
| macOS | ✅ full advertisement packet | ✅ | ✅ | ❌ |
| Windows | ⚠️ `BluetoothLEAdvertisementPublisher` only, no stable GATT server API | ❌ | ✅ | ❌ |

MAC address spoofing is not possible on any modern platform; all other aspects of the cloned profile can be replayed.

## Decision

We will introduce a **peripheral-role advertising abstraction** to Blue Falcon, implemented in three layers:

### Layer 1 — Core types (`:core`)

New types added to `library/core/src/commonMain/`:

- **`BluetoothAdvertiser`** — interface with `startAdvertising(AdvertiseConfig)`, `stopAdvertising()`, `updateCharacteristicValue()`, and reactive flows `state: StateFlow<AdvertiserState>` and `characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest>`.
- **`AdvertiseConfig`** — advertising configuration: local name, service UUIDs, manufacturer data, and a list of `GattServiceConfig`.
- **`GattServiceConfig` / `GattCharacteristicConfig` / `GattDescriptorConfig`** — GATT server tree definition.
- **`CharacteristicProperty`** — enum of READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE.
- **`AdvertiserState`** — enum of Idle, Advertising, Error.
- **`CharacteristicWriteRequest`** — event emitted when a central writes to a hosted characteristic.

Each engine optionally exposes `fun createAdvertiser(): BluetoothAdvertiser`. Engines that do not support advertising return a `NoOpBluetoothAdvertiser`.

### Layer 2 — Platform engines

- **`:engines:android`** — `AndroidBluetoothAdvertiser` using `BluetoothLeAdvertiser` + `BluetoothGattServer`.
- **`:engines:ios`** — `IosBluetoothAdvertiser` using `CBPeripheralManager`.
- **`:engines:macos`** — `MacosBluetoothAdvertiser` using `CBPeripheralManager`.
- **`:engines:windows`** — `WindowsBluetoothAdvertiser` advertising-only (no GATT server); returns `UnsupportedOperationException` on `updateCharacteristicValue`.

### Layer 3 — Broadcast plugin (`:plugins:broadcast`)

`DeviceBroadcastPlugin` takes a `DeviceClone` and a `BluetoothAdvertiser` and:
1. Converts `DeviceClone` → `AdvertiseConfig` (mapping services, characteristics, descriptors and their values).
2. Calls `advertiser.startAdvertising(config)`.
3. Handles incoming `characteristicWriteRequests` by updating state.
4. Exposes a `broadcastState: StateFlow<BroadcastState>` for the UI.

## Consequences

### Positive

- Enables replay/emulation of any scanned BLE device, useful for testing, prototyping, and BLE cloning research.
- Clean separation: the broadcast plugin depends only on the core `BluetoothAdvertiser` interface, not on any engine directly.
- The existing clone plugin is unchanged; broadcast is purely additive.
- `createAdvertiser()` on each engine is optional and clearly documented per-platform.

### Negative

- iOS advertisement packet is heavily restricted by the OS; manufacturer data is silently dropped in the foreground and the advertisement is further stripped in the background.
- MAC address cannot match the original device, so connection-level impersonation is not possible.
- Windows lacks a stable GATT server API, so the Windows implementation only covers advertisement data.
- Requires additional Android permissions (`BLUETOOTH_ADVERTISE` on API 31+).

### Neutral

- A `NoOpBluetoothAdvertiser` is provided for platforms/contexts where advertising is unsupported, allowing the broadcast plugin to compile everywhere.
- The GATT server state (characteristic values) lives inside the `BluetoothAdvertiser` implementation, not in the plugin — the plugin updates values via `updateCharacteristicValue()`.

## Alternatives Considered

### Alternative 1: Merge broadcast into the clone plugin

Add `startBroadcast()` directly to `DeviceClonePlugin`.

**Pros:**
- Single module for the full clone-and-broadcast workflow.

**Cons:**
- Clone plugin would gain a dependency on platform advertising APIs, breaking the clean separation between data capture and advertisement.
- Makes the clone plugin untestable without platform BLE hardware.

**Why not chosen:** Violates single-responsibility; broadcast is a distinct concern from data capture.

### Alternative 2: Expose advertising via `BlueFalconEngine` directly

Add `startAdvertising()` methods alongside the existing central-role methods.

**Pros:**
- Single entry-point for all BLE operations.

**Cons:**
- Conflates central and peripheral roles in one interface.
- Platforms that support only one role would need stub implementations for all peripheral methods.
- Makes the engine interface significantly larger and harder to implement correctly.

**Why not chosen:** The central and peripheral roles are fundamentally different; keeping them separate mirrors the underlying platform APIs and is cleaner.

### Alternative 3: Use a third-party library (e.g. Kable for peripheral role)

**Pros:**
- Leverage existing multiplatform work.

**Cons:**
- Kable's peripheral/server role support is incomplete and not maintained.
- Adds a transitive dependency with its own versioning concerns.
- Reduces control over the exact advertisement packet construction needed for faithful device replay.

**Why not chosen:** The required functionality is small enough to implement directly and gives us full control.

## Implementation Notes

1. `:core` changes are additive and non-breaking.
2. Each engine adds `createAdvertiser()` as an extension on the engine class (not part of `BlueFalconEngine`) to avoid forcing all engines to implement it.
3. The example app adds a "Broadcast" toggle to the existing clone result dialog.
4. Android `BLUETOOTH_ADVERTISE` permission must be added to the example `AndroidManifest.xml`.

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)
- [ADR 0005: BLE Device Cloning Plugin](0005-ble-device-cloning-plugin.md)

## References

- [Android BluetoothLeAdvertiser](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser)
- [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)
- [Apple CBPeripheralManager](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager)
- [Windows BluetoothLEAdvertisementPublisher](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.advertisement.bluetoothleadvertisementpublisher)

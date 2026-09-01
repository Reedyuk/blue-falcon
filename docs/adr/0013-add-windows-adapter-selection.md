# ADR 0013: Add Windows Bluetooth Adapter Enumeration and Selection

**Status:** Accepted

**Date:** 2026-09-01

**Deciders:** Blue Falcon maintainers

## Context

Blue Falcon's common engine API now exposes Bluetooth adapter enumeration and adapter selection so desktop hosts can choose which radio a BLE session uses. Windows is the first target where this matters because a machine can have multiple Bluetooth radios with different capabilities, power states, or driver quality.

The existing Windows engine used the first available Bluetooth radio implicitly and did not expose adapter identity, BLE support flags, or a way to switch the active radio from Kotlin.

## Decision

We will extend the Windows engine JNI bridge and native WinRT implementation to enumerate Bluetooth radios, expose adapter metadata to Kotlin, cache the available radios, and allow consumers to select the adapter used for subsequent Windows Bluetooth operations.

## Consequences

### Positive

- Windows consumers can inspect all available Bluetooth radios.
- Applications can select a preferred BLE adapter explicitly.
- The selected radio becomes the source of adapter state monitoring in the Windows engine.

### Negative

- The Windows JNI/native layer gains more platform-specific state and synchronization.
- Adapter metadata depends on WinRT radio and Bluetooth adapter APIs, which can vary with drivers.
- Tests remain limited to JVM/native integration boundaries outside real Windows hardware.

### Neutral

- Non-Windows engines continue returning unsupported or empty adapter-selection results.
- The public common API remains platform-agnostic and backward-compatible.

## Alternatives Considered

### Alternative 1: Keep implicit first-radio selection only

**Pros:**
- No additional native complexity.
- No new JNI marshaling.

**Cons:**
- Cannot support multi-adapter hosts.
- Hides BLE capability and adapter identity from callers.

**Why not chosen:** It does not satisfy the new common API contract.

### Alternative 2: Expose enumeration but not selection

**Pros:**
- Lower implementation complexity than full selection.
- Allows diagnostic UI to show installed radios.

**Cons:**
- Does not let applications act on the information.
- Leaves the active radio ambiguous.

**Why not chosen:** The feature requires both enumeration and explicit adapter selection.

## Implementation Notes

The Windows engine maps WinRT radio data into common `BluetoothAdapter` values through JNI `BluetoothAdapterData` objects. The native manager caches radios, picks an initial default adapter, and updates radio state monitoring when selection changes.

## Related Decisions

- [ADR 0001: Add Windows 10 Platform Support Using Native WinRT APIs](0001-add-windows-platform-support.md)

## References

- [Windows Setup](../../library/src/windowsMain/WINDOWS.md)
- [WinRT Radio API](https://learn.microsoft.com/windows/uwp/devices-sensors/radios)

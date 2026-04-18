# ADR 0003: Add Nordic FOTA Plugin Using SMP Protocol

**Status:** Accepted

**Date:** 2026-04-12

**Deciders:** Andrew Reed, Community Contributors

**Technical Story:** [FOTA update feature worth adding it?](https://github.com/Reedyuk/blue-falcon/issues/related)

## Context

Users of Blue Falcon working with Nordic Semiconductor chipsets (nRF52, nRF53, nRF91 series) need to perform Firmware Over The Air (FOTA) updates. Currently, this requires integrating a separate Nordic library (McuManager/MCUmgr) alongside Blue Falcon, which creates complexity in managing two BLE stacks simultaneously. After a FOTA update, the device must be disconnected from the Nordic library and reconnected through Blue Falcon for firmware validation.

With the plugin architecture introduced in ADR 0002, Blue Falcon can now support this functionality as an optional plugin. This allows users who need Nordic FOTA to include it without bloating the core library for users who do not.

The Nordic MCUmgr protocol uses the Simple Management Protocol (SMP) over BLE to communicate with device bootloaders. SMP operates on a well-defined GATT service and characteristic, using CBOR-encoded messages to transfer firmware images and manage the update lifecycle.

## Decision

We will implement a Nordic FOTA plugin (`blue-falcon-plugin-nordic-fota`) that provides firmware update capabilities for Nordic chipset devices using the SMP protocol directly over Blue Falcon's BLE operations.

The plugin will:

1. **Implement the SMP protocol** using standard BLE read/write operations provided by Blue Falcon's core engine, without depending on any external Nordic libraries.
2. **Follow the existing plugin pattern** established by the logging, retry, and caching plugins (ADR 0002).
3. **Manage the FOTA state machine**: Idle → Uploading → Confirming → Resetting → Validating → Complete.
4. **Provide progress callbacks** via a `FotaCallback` interface for UI integration.
5. **Support configurable parameters** including chunk size, timeout durations, and auto-confirm/auto-reset behavior.
6. **Use CBOR encoding** for SMP message framing, implemented inline within the plugin to avoid external dependencies.

### SMP Protocol Details

- **SMP Service UUID**: `8D53DC1D-1DB7-4CD3-868B-8A527460AA84`
- **SMP Characteristic UUID**: `DA2E7828-FBCE-4E01-AE9E-261174997C48`
- **Protocol**: CBOR-encoded request/response over BLE writes with notifications for responses
- **Operations**: Image upload (Group 1, ID 0/1), Image state (Group 1, ID 0), Reset (Group 0, ID 5), Echo (Group 0, ID 0)

## Consequences

### Positive

- Users can perform Nordic FOTA updates using only Blue Falcon, eliminating the need for a separate Nordic BLE library
- Seamless reconnection after firmware update since the same Blue Falcon instance manages the connection
- Plugin architecture keeps the core library lightweight — users who don't need FOTA don't pay the cost
- Cross-platform support (Android, iOS, macOS, JS, Windows) since SMP is implemented at the BLE protocol level
- Consistent API experience across all Blue Falcon operations

### Negative

- SMP protocol implementation must be maintained as Nordic updates the protocol
- No access to Nordic-specific optimizations available in their native SDKs
- CBOR encoding/decoding adds implementation complexity within the plugin
- Testing requires either Nordic hardware or comprehensive mocking

### Neutral

- Published as a separate artifact: `dev.bluefalcon:blue-falcon-plugin-nordic-fota`
- Follows the same version scheme as other plugins (`versionPlugins` from gradle.properties)
- Users must explicitly opt-in by adding the plugin dependency

## Alternatives Considered

### Alternative 1: Wrap Nordic's McuManager SDK

Wrap Nordic's official McuManager libraries (Android/iOS) behind a Kotlin Multiplatform interface.

**Pros:**
- Battle-tested Nordic implementation
- Automatic protocol updates from Nordic

**Cons:**
- Platform-specific dependencies break the multiplatform model
- Cannot support JS or Windows targets
- Dual BLE stack management complexity remains
- Large dependency footprint

**Why not chosen:** Defeats the purpose of a unified multiplatform library and doesn't solve the dual-stack problem.

### Alternative 2: Add FOTA to Core Library

Implement FOTA directly in the Blue Falcon core module.

**Pros:**
- Tighter integration with core BLE operations
- Simpler dependency management for users

**Cons:**
- Bloats the core library for all users
- Violates the plugin architecture established in ADR 0002
- Couples Nordic-specific functionality to the core

**Why not chosen:** Contradicts the plugin-based architecture decision and adds unnecessary weight for users who don't need FOTA.

### Alternative 3: External Protocol Library

Create a completely separate library that depends on Blue Falcon for BLE transport.

**Pros:**
- Complete independence from Blue Falcon internals

**Cons:**
- Loses access to plugin interceptors (logging, retry, caching)
- Separate release cycle and version management
- Users must manage additional dependency

**Why not chosen:** The plugin system already provides the right extension point. A plugin benefits from the existing interceptor chain.

## Implementation Notes

- The plugin module lives at `library/plugins/nordic-fota/`
- CBOR encoding is implemented inline using minimal byte-level operations to avoid external dependencies
- The plugin uses `onBeforeWrite`/`onAfterWrite` hooks for intercepting firmware data writes
- Firmware data is chunked according to the peripheral's MTU size for optimal throughput
- The state machine is exposed via `StateFlow<FotaState>` for reactive UI integration

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)

## References

- [MCUmgr SMP Protocol Specification](https://docs.zephyrproject.org/latest/services/device_mgmt/smp_protocol.html)
- [Nordic Semiconductor MCUboot Documentation](https://developer.nordicsemi.com/nRF_Connect_SDK/doc/latest/mcuboot/index.html)
- [CBOR (RFC 8949)](https://www.rfc-editor.org/rfc/rfc8949)
- [Blue Falcon Plugin System](https://github.com/Reedyuk/blue-falcon)

# ADR 0016: Peripheral-Role L2CAP CoC Server (`L2capListener`)

**Status:** Proposed

**Date:** 2026-11-25

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** [Issue #326: L2CAP CoC server is not supported](https://github.com/Reedyuk/blue-falcon/issues/326)

## Context

The central side can open L2CAP connection-oriented channels (`BlueFalcon.openL2capChannel`,
returning `BluetoothSocket`). The peripheral module ([ADR 0007](0007-introduce-production-grade-peripheral-module.md))
can only exchange data through GATT notifications and writes. For bulk transfer (files, images,
mesh message batches) GATT is several times slower than a CoC channel, because every notification
pays ATT overhead and is limited by the ATT MTU.

Both platforms provide a CoC server:

- Android 10 (API 29)+: `BluetoothAdapter.listenUsingL2capChannel()` /
  `listenUsingInsecureL2capChannel()` return a `BluetoothServerSocket` with a dynamic PSM.
  `accept()` blocks until a central connects.
- iOS 11+ / macOS 10.13+: `CBPeripheralManager.publishL2CAPChannelWithEncryption(_:)`, then
  `peripheralManager(_:didPublishL2CAPChannel:error:)` reports the PSM and
  `peripheralManager(_:didOpen:error:)` delivers a `CBL2CAPChannel` with a stream pair.

Neither platform advertises the PSM. Centrals usually read it from a GATT characteristic.

## Decision

### 1. Public API in `blue-falcon-peripheral`

```kotlin
interface BlueFalconPeripheral {
    // ...
    suspend fun publishL2capChannel(secure: Boolean = true): L2capListener // default: throws L2capException
}

interface L2capListener {
    val psm: Int
    val secure: Boolean
    val isOpen: Boolean
    val sockets: Flow<L2capServerSocket>   // single consumer, completes on close
    fun close()
}

interface L2capServerSocket : BluetoothSocket {
    val sessionId: PeripheralSessionId
}

data class PeripheralCapabilities(/* ... */, val l2capServer: Boolean = false)
```

- `L2capServerSocket` extends the existing core `BluetoothSocket`. Transport code handles both
  directions with one type. `BluetoothSocket.peripheral` describes the remote central: `uuid` is
  the session id, and the GATT fields are empty.
- `sessionId` uses the `PeripheralSession.id` format (Android address, Apple `CBCentral`
  identifier), so applications can correlate a channel with a GATT session when there is one.
- The interface method has a default body, so existing `BlueFalconPeripheral` implementations
  still compile. `PeripheralCapabilities` keeps its previous JVM constructor as a hidden overload.

### 2. Lifecycle owned by the common manager

- `publishL2capChannel` is valid only in `Running` and runs under the manager lifecycle mutex.
- `stop()`, `close()` and the terminal backend-event-overflow path close every listener
  **before** the backend stops. Closing a listener unpublishes the native server, closes queued
  and delivered sockets, and completes `sockets`.
- A native server failure (accept error, permission revoked, Bluetooth off) closes the listener and
  completes `sockets` with `L2capException`.

### 3. Bounded queues, loss-free inbound data

Following the bounded-queue pattern of ADR 0007:

- At most 8 listeners per manager, and at most 16 open sockets per listener. Excess incoming
  channels are closed immediately instead of queued.
- Inbound bytes go through `L2capInboundBuffer`: a 64-chunk bounded channel that starts forwarding
  to `incoming` only after the first collector subscribes. When it is full, the reader stops
  reading (blocking `read` on Android, paused stream drain on Apple). The platform's L2CAP credit
  flow control then pushes back on the central. Bytes sent before the application collects are not
  dropped.

### 4. Platform backends

- Android: `AndroidL2capServer` runs the blocking `accept()` loop on `Dispatchers.IO`. Closing the
  native server socket unblocks it. `FrameworkAndroidBluetoothStack.listenL2cap` checks API 29+ and
  `BLUETOOTH_CONNECT` (API 31+).
- Apple: `FrameworkApplePeripheralStack` serializes publications, because Core Bluetooth reports
  `didPublishL2CAPChannel` without a request id. A late publication after a timeout is
  unpublished immediately. Channels opened on unknown PSMs are closed. Retiring the manager
  unpublishes every PSM. `AppleL2capServerSocket` keeps a reference to the `CBL2CAPChannel` while
  open and schedules the streams on the main run loop, like the central-side socket.

## Consequences

### Positive

- Bulk transfer over CoC from the peripheral role on Android and Apple with one common API.
- No unbounded buffering. Deterministic cleanup tied to the existing manager lifecycle.
- Tested through seams (`AndroidL2capServerChannel`, `AppleL2capChannel`, `FakePeripheralBackend`)
  without Bluetooth hardware.

### Negative

- The socket IO pumps duplicate part of the central-side `L2CapSocket` / `AppleL2CapSocket`
  logic. The peripheral module must not depend on the engine modules (ADR 0007), and core has no
  platform source sets. Moving both into a shared module is a possible follow-up.
- The inbound path here is loss-free. The central-side sockets still use `tryEmit` and can drop
  bytes under backpressure, so the two directions currently differ in that respect.
- Android 7–9 (API 24–28) cannot host a CoC server. `capabilities.l2capServer` is `false` there.

### Neutral

- Publishing the PSM to centrals stays an application concern, for example a readable
  characteristic with UUID `ABDD3056-28FA-441D-A470-55A75A52553A`
  (`CBUUIDL2CAPPSMCharacteristicString`).

## Alternatives Considered

### Depend on the engine modules to reuse their socket classes

**Pros:** no duplicated IO code.
**Cons:** breaks the ADR 0007 dependency direction, and peripheral-only apps would inherit the
central engine manifest, including location permissions.
**Why not chosen:** the dependency and permission cost is larger than the duplicated code.

### Unbounded `SharedFlow`/`Channel` for sockets and data

**Why not chosen:** contradicts the bounded-resource rules of ADR 0007. A fast central could
exhaust memory.

## References

- [ADR 0007](0007-introduce-production-grade-peripheral-module.md)
- Android `BluetoothAdapter.listenUsingL2capChannel`
- Apple `CBPeripheralManager.publishL2CAPChannel(withEncryption:)`

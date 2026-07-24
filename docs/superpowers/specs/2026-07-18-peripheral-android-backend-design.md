# Android Peripheral Backend Design

## Context

The peripheral module now has a platform-neutral manager, session runtime, request model, response deadlines, and backend SPI. Android already has `AndroidBluetoothAdvertiser`, but it combines framework access, GATT policy, legacy event translation, and lifecycle management in one class. Extending that class independently would create a second server stack and would let the legacy and production APIs diverge.

This change makes one Android backend authoritative and keeps the existing advertiser API as a compatibility façade over it.

## Goals

- Implement the common `PeripheralBackend` contract on Android.
- Support connectable advertising and a local GATT server on Android API 24+.
- Support multiple centrals, targeted notifications, per-session MTU limits, subscriptions, prepared writes, forced disconnect, and notification readiness.
- Preserve source compatibility for `AndroidBluetoothAdvertiser` and `createBluetoothAdvertiser()`.
- Keep Android Bluetooth callbacks non-blocking and independent of fire-and-forget coroutines.
- Make backend policy testable on the local JVM without Bluetooth hardware or Robolectric.

## Non-goals

- Android process restoration after termination.
- Extended advertising, periodic advertising, PHY selection, or L2CAP.
- Application-level prepared-write assembly or message queueing.
- Apple, JVM, JS, Windows, or Linux peripheral implementations.
- Changing the common peripheral contracts approved in the preceding stacked PR unless an Android platform constraint proves that a correction is required.

## Public API

Android exposes the production manager through:

```kotlin
fun createBlueFalconPeripheral(
    context: Context,
    logger: Logger? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): BlueFalconPeripheral
```

The factory constructs `DefaultBlueFalconPeripheral` with `AndroidPeripheralBackend` and the caller-provided coroutine context.

`AndroidBluetoothAdvertiser` and `createBluetoothAdvertiser()` remain available and become deprecated compatibility APIs. They delegate to `AndroidPeripheralBackend`; they do not open a separate GATT server implementation.

## Component boundaries

### AndroidPeripheralBackend

`AndroidPeripheralBackend` implements the common backend SPI and owns platform-neutral Android server policy:

- lifecycle and generation validation;
- GATT service startup sequencing;
- session, MTU, subscription, and pending-notification registries;
- conversion from stack events to common backend requests;
- notification and disconnect result mapping;
- deterministic rollback, stop, and close.

It never launches work from a callback. Callback events are copied and delivered synchronously to `PeripheralBackendEventSink`.

### AndroidBluetoothStack

`AndroidBluetoothStack` is an internal test seam. It exposes small immutable service definitions, stack events, response operations, targeted notification operations, and advertising lifecycle operations. It contains no business policy.

`FrameworkAndroidBluetoothStack` is the only component that directly uses `BluetoothManager`, `BluetoothAdapter`, `BluetoothGattServer`, `BluetoothGattServerCallback`, `BluetoothLeAdvertiser`, and `AdvertiseCallback`.

Framework objects are mapped to internal stable identifiers before events enter the backend. Mutable `ByteArray` inputs are copied at the boundary.

### AndroidBluetoothAdvertiser compatibility façade

The compatibility façade owns one `AndroidPeripheralBackend` and a legacy event sink. It:

- maps backend lifecycle to `AdvertiserState`;
- emits legacy `CharacteristicWriteRequest` with `tryEmit`;
- automatically responds to legacy read, write, descriptor, and execute requests;
- maintains legacy attribute values for subsequent reads;
- tracks active sessions and broadcasts `updateCharacteristicValue()` through targeted backend notifications.

The façade introduces no independent Android Bluetooth implementation and no private coroutine scope.

## Startup and shutdown

`start()` performs these steps in order:

1. Validate that the backend is not closed and allocate a new generation.
2. Validate adapter availability, enabled state, advertising support, and required permissions.
3. Open the GATT server.
4. Add each configured service sequentially, awaiting its `onServiceAdded` result before adding the next service. Android explicitly requires this sequencing.
5. Start connectable advertising and await the advertising callback.
6. Publish successful completion to the common runtime.

Service-add and advertising waits use an internal finite 10-second operation timeout so a broken vendor stack cannot suspend startup forever. The timeout is injectable for tests but is not part of the public API.

Any exception, timeout, or cancellation invalidates the generation and performs complete rollback: advertising is stopped, the GATT server is cleared and closed, and all session state is discarded.

`stop()` is idempotent and permits a later `start()`. `close()` is idempotent and terminal. Late callbacks from invalid generations are ignored. A late request received while teardown is in progress receives `GATT_FAILURE` so that the remote central is not left waiting for the ATT timeout.

## Sessions and MTU

`BluetoothDevice.address` is the Android session key for the lifetime of a connection. The backend keeps the corresponding device handle inside `AndroidBluetoothStack`; common code receives only `PeripheralSessionId`.

Successful connection callbacks open a session. Disconnect callbacks remove all MTU, subscription, and pending-notification state before publishing session closure.

`onMtuChanged(device, mtu)` publishes `max(0, mtu - 3)` as `maximumUpdateValueLength`. Before an MTU callback, the backend uses the ATT default payload limit of 20 bytes.

## GATT requests and responses

Characteristic and descriptor reads and writes are mapped one-for-one to the common backend request types. Execute-write callbacks become `BackendExecuteWriteRequest`.

Prepared-write fragments are not assembled in the backend except for the two-byte CCCD value needed to maintain transport subscription metadata. Every fragment still retains its offset and `preparedWrite = true`; the application decides whether to accept, store, execute, or cancel the application transaction.

Every response-required callback receives a responder bound to its device, request ID, and offset. Common one-shot response handling guarantees one terminal application response. `GattResponseStatus` maps to the closest Android GATT status constant. A false `sendResponse()` result is reported as a platform failure.

## CCCD subscriptions

The backend recognizes the standard CCCD UUID and maps its values as follows:

- `ENABLE_NOTIFICATION_VALUE`: subscribe the session to the characteristic in notification mode;
- `ENABLE_INDICATION_VALUE`: subscribe in indication mode;
- `DISABLE_NOTIFICATION_VALUE`: remove the subscription.

The descriptor write remains visible to the application. For response-required writes, subscription state changes are published only after the application responds with success. A failed or expired response does not mutate subscriptions. A non-prepared write without a response commits when it is delivered because Android provides no later acceptance signal.

Prepared CCCD fragments are staged per session. They commit only after successful fragment responses followed by a successful execute-write response. Cancellation, rejection, expiry, disconnect, stop, or close discards the staged value.

The common session API exposes the subscribed characteristic set. The backend retains notification-versus-indication mode internally to validate targeted sends.

## Notification backpressure

`notify()` validates the session, characteristic, subscription mode, and negotiated payload limit. It permits one pending notification or indication per session.

- An accepted platform send returns `NotificationResult.Sent` and marks the session pending.
- A second send before `onNotificationSent` returns `NotificationResult.Busy`.
- A missing session returns `NotificationResult.Disconnected`.
- Unsupported characteristic properties or subscription mode return `NotificationResult.Unsupported`.
- Permission and platform failures return `NotificationResult.Failed`.

API 33+ uses the memory-safe `notifyCharacteristicChanged(device, characteristic, confirm, value)` overload. Older APIs copy the value into the characteristic before calling the deprecated overload. `onNotificationSent` clears the pending flag and publishes session-scoped notification readiness regardless of the platform status; a non-success status is additionally published as a platform failure.

## Concurrency

Framework callbacks may arrive on arbitrary Binder threads. Backend maps are guarded by one JVM monitor. Code captures the minimum immutable snapshot while holding the monitor, releases it, and only then invokes Android APIs or the common event sink.

Suspend lifecycle methods await explicit callback completions with structured cancellation. They do not hold the monitor across suspension. Cancellation is rethrown after rollback.

No callback calls `runBlocking`, waits on a coroutine, or launches unowned work.

## Capabilities

For supported Android hardware the backend reports:

- local GATT server: supported;
- connectable advertising: supported when multiple advertising is available;
- multiple centrals: supported;
- targeted notifications: supported;
- notification readiness: supported;
- maximum update value length: supported;
- forced disconnect: supported;
- connection lifecycle visibility: supported;
- prepared writes: supported;
- state restoration: unsupported.

Unsupported hardware remains constructible so callers can inspect capabilities. `start()` fails with `PeripheralUnsupportedException` when required server or advertising functionality is absent.

## Error policy

- Missing Android Bluetooth permissions map to `BluetoothPermissionException`.
- A disabled adapter maps to `BluetoothNotEnabledException`.
- Missing server or advertising support maps to `PeripheralUnsupportedException`.
- Service-add and advertising callback failures fail startup with an Android-specific cause containing the platform status code.
- Callback-path `SecurityException` and platform failures are delivered through `onPlatformFailure`.
- Operation-path failures become `NotificationResult.Failed` or `DisconnectResult.Failed`.

Errors are never converted into silent success, and `CancellationException` is never swallowed.

## Testing strategy

`androidUnitTest` uses a fake `AndroidBluetoothStack`; it requires no device and no Robolectric. Tests cover:

- sequential service registration and advertising ordering;
- startup rollback on service failure, advertising failure, and cancellation;
- restart after stop and rejection after close;
- stale-generation callbacks;
- session opening, closing, and default/negotiated MTU limits;
- every characteristic, descriptor, and execute request mapping;
- response status mapping and failed platform responses;
- CCCD enable, indication, disable, rejection, and expiry behavior;
- targeted notification validation, MTU rejection, per-session backpressure, and readiness;
- targeted disconnect;
- compatibility façade state, legacy reads/writes, and broadcast updates;
- proof that production and compatibility entry points both use the same backend implementation.

Verification commands:

```bash
./gradlew :peripheral:testDebugUnitTest
./gradlew :peripheral:jvmTest
./gradlew :peripheral:build :plugins:broadcast:build
```

## Delivery

The implementation is developed on `codex/peripheral-android-backend`, stacked on `codex/peripheral-common-api`. Its pull request targets the common API branch. Apple support remains a later stacked change.

## References

- [BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)
- [BluetoothGattServerCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback)
- [BluetoothLeAdvertiser](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser)

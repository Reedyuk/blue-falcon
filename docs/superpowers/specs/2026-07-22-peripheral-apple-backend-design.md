# Production Apple Peripheral Backend Design

**Date:** 2026-07-22
**Status:** Approved for implementation
**Branch:** `codex/peripheral-apple-backend`
**Stacked on:** `codex/peripheral-android-backend`
**Related ADR:** `docs/adr/0007-introduce-production-grade-peripheral-module.md`

## Objective

Implement the production `BlueFalconPeripheral` backend once in `appleMain` for iOS and macOS.
The backend must provide application-controlled ATT requests, observable central sessions,
subscription tracking, targeted updates, explicit backpressure, per-central maximum update length,
and opt-in Core Bluetooth state restoration. The existing `AppleBluetoothAdvertiser` remains as a
deprecated source-compatible facade over the production runtime.

This change also extends the common request contract with an atomic characteristic-write batch.
Core Bluetooth delivers writes as a list and requires exactly one response for the whole callback;
the existing singular request cannot represent that contract safely.

## Scope

Included:

- shared iOS/macOS implementation in `appleMain`;
- an internal Core Bluetooth stack seam and a production `CBPeripheralManager` adapter;
- common atomic characteristic-write batch request API;
- lifecycle, sessions, subscriptions, reads, writes, targeted updates, and readiness;
- opt-in state restoration driven by `PeripheralConfig.restorationIdentifier`;
- compatibility behavior for `AppleBluetoothAdvertiser`;
- native tests, target compilation checks, and Apple startup/restoration documentation.

Excluded:

- the `example-3.0` GATT server UI, which the maintainer approved deferring;
- Windows, Linux, JavaScript, and Wasm peripheral backends;
- the bounded queue plugin;
- Android behavioral changes beyond compiling against the batch request extension;
- automatic version changes, because release scripts own the version bump;
- a promise that iOS will relaunch every application for restoration; relaunch eligibility remains
  operating-system policy.

## Architecture

The implementation follows the same boundary pattern as the Android backend without forcing the
two platforms into one policy implementation:

```text
BlueFalconPeripheral
        |
DefaultBlueFalconPeripheral
        |
ApplePeripheralBackend
        |
ApplePeripheralStack
        |
FrameworkApplePeripheralStack
        |
CBPeripheralManager
```

`FrameworkApplePeripheralStack` is the only component that owns or exposes Core Bluetooth objects.
It creates the peripheral manager, owns its delegate, builds and restores the GATT database, and
turns callbacks into copied platform-neutral events. `ApplePeripheralBackend` implements
`PeripheralBackend` and owns lifecycle generation, session policy, subscription state, request
translation, notification validation, and common event delivery.

The framework adapter and backend are separate so lifecycle and policy can be tested with a fake
stack without Bluetooth hardware. Raw `CBCentral`, `CBATTRequest`, `CBMutableCharacteristic`,
`CBMutableService`, `NSData`, and `NSError` instances never cross the stack boundary.

The public Apple factory constructs `DefaultBlueFalconPeripheral` around the Apple backend:

```kotlin
fun createBlueFalconPeripheral(
    logger: Logger? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): BlueFalconPeripheral
```

The restoration identifier stays in `PeripheralConfig`; it is not duplicated in the factory.

## Common Atomic Write Batch

Add a public `GattCharacteristicWriteBatchRequest` containing one non-empty list of
`GattCharacteristicWrite` values and one non-null `GattResponseHandle`:

```kotlin
class GattCharacteristicWriteBatchRequest(
    override val session: PeripheralSession,
    writes: List<GattCharacteristicWrite>,
    override val response: GattResponseHandle,
) : GattServerRequest

class GattCharacteristicWrite(
    val serviceId: GattServiceId,
    val characteristicId: GattCharacteristicId,
    val offset: Int,
    value: ByteArray,
)
```

Both the list and every byte array are defensively copied. An empty batch is rejected at
construction. Add `CharacteristicWriteBatch` to `GattRequestType` and an internal
`BackendCharacteristicWriteBatchRequest` so the common request registry owns one deadline and one
terminal response for the complete platform callback.

An Apple callback containing one write remains a regular `GattCharacteristicWriteRequest` to keep
the common single-write path uniform across Android and Apple. A callback containing two or more
writes becomes one batch request. The application must accept or reject all operations together;
the backend never applies only part of a batch.

This is required by bitchat. Its Apple transport issues sequential write-without-response chunks,
and Core Bluetooth may deliver several chunks in one write callback. Rejecting multi-write
callbacks can therefore drop transport fragments under load.

## Lifecycle and Concurrency

The peripheral manager and its delegate use a dedicated serial dispatch queue. Core Bluetooth
operations are issued on that queue. Backend mutable state is protected independently and tagged
with a monotonically increasing lifecycle generation so callbacks from an earlier start cannot
mutate a restarted manager.

`start(config)` performs these operations in order:

1. Validate that the backend is stopped and install the event sink for a new generation.
2. Create `CBPeripheralManager` with its delegate and, when configured, the restoration identifier.
3. Wait for `CBManagerStatePoweredOn`; fail on unsupported or unauthorized states and apply a
   finite startup timeout to states that do not become usable.
4. Adopt a compatible restored GATT database or publish the configured services sequentially.
5. Reconstruct restored subscriptions before reporting the manager as running.
6. Reuse restored advertising when already active, otherwise start advertising and await its
   delegate result.
7. Return only after the backend is ready and the common manager can enter `Running`.

Startup failure performs non-cancellable rollback: stop advertising, remove services, detach the
active generation, clear session/subscription registries, and release pending platform requests.
The original failure is returned from `start` and asynchronous failures are also sent through
`onPlatformFailure` where appropriate.

`stop()` is idempotent and restartable. It ends advertising, removes services, expires platform
requests through common shutdown, and clears sessions. `close()` performs the same cleanup and
marks the backend terminal. `disconnect(sessionId)` returns `Unsupported` because
`CBPeripheralManager` provides no API to force-disconnect a remote central.

Application request collectors are never invoked on the Core Bluetooth delegate queue. Every
inbound and outbound byte array is copied at the public and stack boundaries.

## GATT Database Mapping

Each configured service becomes a primary `CBMutableService`. Characteristics use dynamic values
(`value = nil`) so reads and writes always pass through the application-controlled request API.
Characteristic properties map to `CBCharacteristicProperties`; explicit permissions map to
`CBAttributePermissions`, while zero permissions derive readable/writeable access from the
properties.

Core Bluetooth creates CCCD automatically for notifying or indicating characteristics, so UUID
`0x2902` descriptors are never added manually. Other configured descriptors are created with a
non-null copied value. Core Bluetooth does not provide dynamic descriptor request callbacks in the
peripheral role, so descriptor values remain static and the Apple backend does not claim dynamic
descriptor read/write support.

iOS advertising includes only the local name and service UUIDs supported by Core Bluetooth.
Unsupported manufacturer data and TX-power requests are not reported as successfully transmitted.
macOS uses the same Core Bluetooth API surface and the same shared mapping.

## Sessions and Subscriptions

`CBCentral.identifier.UUIDString` becomes the opaque `PeripheralSessionId`. A session opens on the
first reliable event containing a central: read, single write, batch write, or subscription. The
session receives `CBCentral.maximumUpdateValueLength` immediately.

Subscription callbacks maintain a set of characteristic IDs per session. A final unsubscribe
publishes an empty subscription set but does not claim a physical disconnect. Because Apple has no
complete peripheral-role disconnect callback, `connectionLifecycleVisibility` is false and the
common inactivity timeout removes unsubscribed request-only sessions. Active subscriptions prevent
inactivity eviction.

The Apple capabilities are:

- local GATT server: supported;
- connectable advertising: supported;
- multi-central operation: supported;
- targeted notifications: supported;
- notification readiness: manager-scoped and supported;
- maximum update length: per central and supported;
- forced disconnect: unsupported;
- complete connection lifecycle visibility: unsupported;
- prepared writes: unsupported by the exposed Core Bluetooth delegate contract;
- state restoration: supported when a stable identifier is supplied.

## ATT Requests and Responses

Read and write callbacks are registered synchronously with the common request registry before
delivery to application code. The common one-shot handle owns the deadline. If the application
does not answer, the existing fallback timer responds with Apple `unlikelyError`, expires the
handle, and emits the timeout event.

Read success assigns the response bytes to the original request and then calls
`respondToRequest`. Other statuses map to the closest `CBATTError` value. A write batch retains only
the platform token needed to respond to the first `CBATTRequest`, as required by Core Bluetooth;
the application sees copied platform-neutral operations.

Structural failures that cannot be represented safely, including an empty platform batch or an
unknown hosted characteristic, are rejected immediately with an appropriate ATT error and are not
delivered as partially valid application requests. Late callbacks after stop are rejected where a
platform response is still legal; otherwise they are ignored and surfaced as an observable
platform failure when useful.

## Targeted Updates and Backpressure

Before sending an update, the backend verifies:

- the session is active;
- the characteristic exists and supports the requested notification mode;
- the session is subscribed to that characteristic;
- the payload does not exceed that central's `maximumUpdateValueLength`.

The framework stack calls `updateValue` with exactly the selected `CBCentral`. A true result maps
to `NotificationResult.Sent`; false maps to `Busy`. The backend does not retain a hidden retry
queue. `peripheralManagerIsReadyToUpdateSubscribers` emits `NotificationReadiness.Manager`, because
Core Bluetooth does not identify which central released transmit capacity.

The subscription callback does not reveal whether the central selected notification or indication
when a characteristic supports both. `NotificationMode` is therefore validated against the
configured characteristic properties, while Core Bluetooth selects the actual ATT update behavior
from the central's subscription state.

## State Restoration

When `PeripheralConfig.restorationIdentifier` is non-null, it is passed as
`CBPeripheralManagerOptionRestoreIdentifierKey` during manager creation. The application owns the
identifier and must persist and reuse it across launches.

`willRestoreState` captures restored services and advertising data. The framework adapter compares
the restored UUID topology with the requested config:

- matching topology: retain the restored services and rebuild characteristic lookup tables;
- mismatching topology: remove the restored services and publish the requested config from
  scratch.

For retained services, `subscribedCentrals` rebuilds sessions, per-central maximum lengths, and
subscription sets. If the restored manager is already advertising, startup does not duplicate the
advertising call.

Restoration-enabled applications must create the `BlueFalconPeripheral` and invoke `start(config)`
during early application startup with the same identifier. The migration guide will cover UIKit
`application(_:didFinishLaunchingWithOptions:)` and SwiftUI/scene-based startup. Scene-based apps
must persist the identifier themselves because launch options are not a reliable source.

State restoration capability means the backend participates correctly when Core Bluetooth makes
restored state available. It does not guarantee OS relaunch eligibility.

## Legacy Compatibility

`AppleBluetoothAdvertiser` remains public and source-compatible but becomes deprecated in favor of
`createBlueFalconPeripheral`. It delegates lifecycle and updates to the production runtime.

The facade preserves legacy behavior:

- it maintains dynamic characteristic values;
- read requests are answered from the stored value with offset validation;
- single and batch writes update stored values and receive one success response;
- legacy write events continue to be emitted;
- update calls target every active subscribed session and retain the legacy best-effort surface;
- stop remains restartable.

The compatibility facade cancels request and state collectors whenever advertising stops. New code
is directed to explicit common sessions, request handles, and notification results.

## Testing

All behavior is developed test-first.

Common tests cover batch construction, defensive copies, request conversion, session ownership,
deadline fallback, bounded delivery overflow, and request-type reporting.

Apple backend tests use `FakeApplePeripheralStack` and cover:

- lifecycle ordering, rollback, restart, idempotent stop, terminal close, and stale generations;
- session creation from every reliable event;
- maximum update length and subscription state;
- single reads/writes and atomic multi-write requests;
- one-shot response behavior and timeout fallback;
- targeted update validation, payload bounds, busy results, and manager readiness;
- unsupported disconnect and prepared-write capabilities;
- restored topology adoption, mismatch rebuild, restored advertising, and restored subscriptions.

Framework mapping tests cover UUIDs, properties, permissions, ATT status mapping, dynamic values,
CCCD filtering, restored object indexing, and copied NSData/ByteArray boundaries. Compatibility
tests preserve the old advertiser contract.

Verification includes common tests, host macOS native tests, compilation for iOS arm64, iOS
simulator arm64, iOS x64, macOS arm64, and macOS x64, plus the complete `:peripheral:build` and
dependent broadcast plugin build. Hardware-only two-central and restoration-relaunch scenarios are
documented for manual verification rather than claimed as CI coverage.

## Pull Request Communication

The pull request will explicitly call out the common API addition:

> CoreBluetooth delivers characteristic writes as an atomic request array and requires exactly one
> ATT response for the entire callback. The existing singular common request type could not
> represent this without either responding too early or incorrectly applying part of a batch. This
> PR therefore adds `GattCharacteristicWriteBatchRequest`. This is also required by bitchat, whose
> high-throughput write-without-response transport may be delivered by CoreBluetooth as multiple
> requests in one callback.

The PR also states that the example update is intentionally deferred with maintainer approval and
that release scripts, rather than this branch, own version changes.

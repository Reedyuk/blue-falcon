# ADR 0007: Introduce a Production-Grade Peripheral/GATT Server Module

**Status:** Proposed

**Date:** 2026-07-16

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** [Issue #231: BLE transport gaps required for a bidirectional peer-to-peer transport](https://github.com/Reedyuk/blue-falcon/issues/231)

## Context

Blue Falcon 3.5.0 contains an initial peripheral-role API introduced by
[ADR 0006](0006-ble-device-broadcast-plugin.md):

- `BluetoothAdvertiser`;
- `AdvertiseConfig` and the local GATT database configuration types;
- `CharacteristicWriteRequest`;
- `AndroidBluetoothAdvertiser`;
- `AppleBluetoothAdvertiser`;
- the clone/broadcast plugin integration.

That API proved the platform feasibility of advertising and hosting a local GATT database, but
it was designed for device replay rather than production multi-central transport. In particular,
the current abstraction:

- combines advertisement lifecycle and GATT server lifecycle in one small interface;
- does not expose connection/session-scoped remote centrals;
- broadcasts characteristic updates instead of supporting a targeted session;
- does not model subscriptions as public state;
- does not expose notification backpressure or readiness;
- does not expose negotiated maximum notification lengths per central;
- automatically accepts ATT requests before application business logic can decide the response;
- does not provide deterministic request deadlines or one-shot response ownership;
- has no explicit restoration contract;
- owns unbounded or implicit buffering policies that cannot satisfy every application.

Production uses such as bidirectional peer-to-peer transports require multiple remote centrals,
simultaneous central and peripheral roles, targeted notifications, explicit ATT responses, and
deterministic resource cleanup. Applications may also need specialized bounded or priority queues,
but those policies do not belong in the low-level transport implementation.

Blue Falcon 3.0 established two relevant principles:

1. the client core must remain lightweight and platform-independent; and
2. optional functionality should be delivered through independent modules and plugins.

The maintainers confirmed in issue #231 that:

- multi-central GATT server support is in scope;
- central and peripheral managers must be separate;
- connection/session-scoped objects are preferred over opaque global identifiers;
- engines must expose readiness/busy information;
- sequential queueing belongs in an optional official plugin;
- the existing 3.5.0 APIs should be moved and evolved, not duplicated;
- iOS and macOS should share their `CBPeripheralManager` implementation.

### Goals

- Provide a production-grade, optional local GATT server module.
- Support simultaneous central/client and peripheral/server roles without shared mutable state.
- Make multi-central sessions, subscriptions, ATT requests, targeted updates, and backpressure
  explicit in the common API.
- Preserve application control over ATT responses and queueing policy.
- Implement Android, iOS, and macOS first.
- Keep the common contract extensible to Windows and Linux implementations.
- Fail explicitly on targets that cannot host a local GATT server.

### Non-goals

The peripheral module will not own:

- application framing or fragmentation;
- application peer identity or routing;
- deduplication;
- redundant-link selection;
- retry/backoff across peers;
- persistence or store-and-forward behavior;
- protocol-specific encryption or authentication;
- automatic central/client connections.

## Decision

### 1. Add two optional artifacts

We will add:

- `dev.bluefalcon:blue-falcon-peripheral` — a single Kotlin Multiplatform artifact containing
  the common peripheral API and platform variants;
- `dev.bluefalcon:blue-falcon-plugin-queue` — an optional bounded sequential notification
  queue built on the low-level peripheral API.

The dependency direction will be:

```text
blue-falcon-core
        ↑
blue-falcon-peripheral
        ↑
blue-falcon-plugin-queue
```

`blue-falcon-core` will not depend on either new artifact. Applications that only use the
central/client role will not receive peripheral APIs, platform bridges, permissions, or lifecycle
code.

The application may construct `BlueFalcon` and `BlueFalconPeripheral` together. They will own
separate state, connection collections, coroutine scopes, and platform delegates.

### 2. Move and evolve the 3.5.0 peripheral APIs

The existing peripheral-role types and implementations will move out of `blue-falcon-core` and
the platform engine modules into `blue-falcon-peripheral`. The broadcast plugin will depend on
the new module.

This move will target the next major release. We will not keep deprecated wrappers in
`blue-falcon-core`, because doing so would either retain two competing APIs or introduce the
forbidden `core -> peripheral` dependency. A migration guide will document the dependency,
package, and API changes.

The initial extraction will preserve existing behavior before the types are evolved into the
session/manager design. There will be only one peripheral implementation stack at every stage.

### 3. Introduce a separate peripheral manager

The common root contract will follow this shape:

```kotlin
interface BlueFalconPeripheral {
    val state: StateFlow<PeripheralManagerState>
    val capabilities: PeripheralCapabilities
    val sessions: StateFlow<Set<PeripheralSession>>
    val requests: Flow<GattServerRequest>
    val events: Flow<PeripheralEvent>
    val notificationReadiness: Flow<NotificationReadiness>

    suspend fun start(config: PeripheralConfig)
    suspend fun stop()
    suspend fun close()
}
```

`PeripheralManagerState` will distinguish at least:

- `Stopped`;
- `Starting`;
- `Running`;
- `Stopping`;
- `Failed`;
- `Closed`.

`start` is valid only from `Stopped`. It registers the complete local GATT database before
starting connectable advertising. If any registration or advertising step fails, the manager
rolls back resources created by that invocation and enters `Failed`.

`stop` is idempotent and reusable. It completes only after advertising has stopped, sessions and
pending operations have been cancelled, services have been unregistered, and the manager has
entered `Stopped`. A subsequent `start` is permitted.

`close` is an idempotent suspending terminal operation. It may be called from any non-closed state,
performs the same deterministic cleanup as `stop`, releases the manager-owned scope and platform
delegate, and completes only after the manager has entered `Closed`. Once the terminal transition
begins, critical teardown is completed even if the caller is cancelled. No cleanup continues in the
background after `close` returns, and implementations must not use `runBlocking` or fire-and-forget
cleanup to bridge this contract to a non-suspending API.

### 4. Represent remote centrals as scoped sessions

`PeripheralSession` represents a remote central known to the local GATT server:

```kotlin
interface PeripheralSession {
    val id: PeripheralSessionId
    val state: StateFlow<SessionState>
    val subscriptions: StateFlow<Set<GattCharacteristicId>>
    val maximumUpdateValueLength: StateFlow<Int?>
    val notificationReady: Flow<Unit>

    suspend fun notify(
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode = NotificationMode.Notification,
    ): NotificationResult

    suspend fun disconnect(): DisconnectResult
}
```

`PeripheralSessionId` is an opaque value object. Raw `BluetoothDevice`, `CBCentral`, WinRT,
and D-Bus objects will not cross the common boundary. Applications may map the session ID to their
own link or peer identity, but Blue Falcon will not interpret that mapping.

Platforms expose different connection visibility. A session therefore begins on the first
reliable platform event that identifies a remote central: a connection, subscription, read, or
write. The API will not claim that every session corresponds to an independently observable
physical connection.

When a platform exposes an authoritative disconnect or final unsubscribe event, that event closes
the session. Platforms such as Apple do not expose a complete peripheral-role disconnect callback
for every request-only central. The manager therefore also supports a configurable finite
inactivity timeout for sessions that have no active subscriptions or in-flight operations. An
active subscription prevents inactivity eviction.

Each session owns a child coroutine scope. Session termination cancels pending ATT responses,
queued sends, and other work owned by that session.

On Android, a newly created session exposes the default ATT notification payload limit of 20 bytes
until the remote device negotiates a different MTU. The backend listens to
`BluetoothGattServerCallback.onMtuChanged` for each device and atomically updates only that
session's `maximumUpdateValueLength` to `negotiatedMtu - 3`. The negotiated value is discarded
when the session closes. The backend rejects an oversized notification before invoking the Android
platform API instead of relying on device- or SDK-specific truncation behavior.

### 5. Make subscriptions and ATT responses explicit

The manager will expose characteristic and descriptor read/write requests as
`GattServerRequest` values. Requests include:

- the owning session;
- service, characteristic, and optional descriptor identifiers;
- offset and request metadata;
- a copied value for writes;
- prepared-write metadata where the target supports it;
- a nullable one-shot response handle when ATT permits or requires a response.

The owning session is non-null and part of the common contract rather than optional metadata:

```kotlin
interface GattServerRequest {
    val session: PeripheralSession
    val sessionId: PeripheralSessionId
        get() = session.id
}
```

Every characteristic, descriptor, and execute-write request can therefore be authorized and
routed before application business logic reads or mutates a value. A raw platform device object
is never required for that decision.

The application, rather than the platform implementation, selects the ATT response status and
value. A response handle has exactly one successful terminal operation. Repeated responses return
`AlreadyResponded`; responses after timeout or session closure return `Expired`.

Request delivery is bounded. If the application cannot accept a request that requires a response,
the platform implementation responds with an appropriate resource/error status. A
write-without-response cannot be rejected over ATT, so overflow is reported as an observable
`PeripheralEvent.RequestDropped` event instead of causing unbounded memory growth or a silent
drop. Other asynchronous lifecycle, subscription, and platform failures that do not belong to a
specific suspending call are also reported through `events`.

Request response deadlines are configurable. Each platform delegate wrapper synchronously
registers a response-required request with a manager-owned request registry before publishing it to
`requests`. The registry arms the fallback deadline and owns the atomic response state. If the
application has not completed the handle before the deadline, the registry wins the one-shot
response race, sends the platform-appropriate generic ATT error where the platform still permits
it (for example, Android `GATT_FAILURE` or an Apple ATT unlikely error), invalidates the handle, and
emits a timeout event. Application exceptions, cancellation, a slow collector, or an uncollected
`requests` flow cannot disable this fallback. The deadline mechanism is shared common logic;
platform wrappers provide only the final response operation while translating the callback.

### 6. Expose targeted notifications and backpressure

`PeripheralSession.notify` targets one session and returns:

- `Sent` — the platform stack accepted or completed the update at the strongest completion
  level exposed by that platform;
- `Busy` — no data was accepted and the caller must wait for readiness before retrying;
- `Disconnected`;
- `Failed(cause)`.

`Sent` does not mean end-to-end application delivery.

Readiness can be session-scoped or manager-scoped:

```kotlin
sealed interface NotificationReadiness {
    data object Manager : NotificationReadiness
    data class Session(val sessionId: PeripheralSessionId) : NotificationReadiness
}
```

Manager scope is required because Apple
`peripheralManagerIsReadyToUpdateSubscribers` does not identify a central. A session's
`notificationReady` flow is a convenience projection of the manager flow. Readiness is a hint
to retry, not a reservation of a platform write slot.

The platform backend will not hide an unbounded notification queue. It will serialize only the
operations required by the platform contract and surface `Busy` when it cannot accept the value.

### 7. Publish an optional bounded QueuePlugin

`blue-falcon-plugin-queue` will provide a suspending queued-send operation over
`PeripheralSession`.

The existing `BlueFalconPlugin` contract is specific to central/client operations. We will not
add peripheral callbacks to that interface. Instead, `blue-falcon-peripheral` will define a
small `PeripheralPlugin` contract and registry with installation and lifecycle conventions that
mirror the existing plugin system:

```kotlin
val peripheral = BlueFalconPeripheral {
    install(QueuePlugin) {
        // Finite queue limits and overflow policy.
    }
}
```

This keeps server types out of `blue-falcon-core` while retaining the Blue Falcon plugin
configuration style.

The plugin will implement:

- a bounded FIFO per session;
- a bounded total memory budget;
- immediate continued draining while `notify` returns `Sent`;
- suspension of a readiness scope only after `notify` returns `Busy`;
- resumption of a busy scope only after a matching readiness event;
- a loss-free handoff from `Busy` to waiting: the plugin subscribes to or rechecks the readiness
  scope so an event emitted between the failed attempt and suspension cannot be missed;
- platform-aware acceptance windows: Android may expose `Busy` until `onNotificationSent`, while
  Apple may accept multiple updates before its transmit queue becomes full;
- fair round-robin scheduling across sessions;
- at most one item per session in each scheduling pass before the scheduler advances to the next
  session, preventing a continuously writable session from starving others;
- cancellation of an item that has not yet been submitted when its caller is cancelled;
- cancellation of all session items when the session closes;
- `RejectNewest` as the default overflow policy.

If at least one item is accepted during a scheduling pass, the plugin immediately begins the next
round-robin pass without waiting for a flow emission. It waits on `notificationReadiness` only when
all currently eligible readiness scopes are busy. This avoids a coroutine wake-up between every
accepted packet while preserving fairness and bounded memory.

The plugin will never silently discard data. Queue overflow returns `QueueFull`. A value larger
than `maximumUpdateValueLength` returns `PayloadTooLarge`.

The plugin will not fragment values, add priorities, reconnect peers, retry permanent failures, or
persist data. Applications needing those policies can use the low-level session API directly.

### 8. Expose capabilities instead of no-op behavior

`PeripheralCapabilities` will describe support for at least:

- local GATT server;
- connectable advertising;
- multi-central operation;
- targeted notifications;
- notification readiness;
- maximum update length;
- forced central disconnect;
- connection lifecycle visibility;
- prepared writes;
- state restoration.

Calling an unavailable operation returns a typed `Unsupported` result or error. Implementations
must not report success for a no-op.

### 9. Adopt this platform roadmap

| Target | Backend | Initial implementation | Important capability notes |
|---|---|---:|---|
| Android | `BluetoothGattServer` + `BluetoothLeAdvertiser` | Yes | Targeted notifications, per-device MTU, `onNotificationSent`, forced disconnect |
| iOS | `CBPeripheralManager` | Yes | Targeted notifications, manager-wide readiness, maximum update length, opt-in restoration |
| macOS | shared Apple implementation | Yes | Shared with iOS in `appleMain`; platform lifecycle hooks may remain target-specific |
| Windows | WinRT `GattServiceProvider` | Future | GATT server and targeted notifications are available; implementation follows a separate issue |
| Linux | BlueZ D-Bus | Future | Local GATT is available; standard notifications are shared and may not support targeted delivery |
| JavaScript/Wasm browser | Web Bluetooth | Unsupported | Browser Web Bluetooth exposes the central/client role, not a local GATT server |

The first implementation milestone includes only Android, iOS, macOS, and the queue plugin.
Windows and Linux variants will initially return explicit `Unsupported` results. Their future
implementations must fit the approved common contract and will be tracked separately.

Browser targets will remain explicitly unsupported until a standardized browser peripheral/GATT
server API exists. A native companion process or browser extension is outside this ADR.

The new decision corrects the Windows capability statement in ADR 0006: supported Windows
versions expose a local GATT server through `GattServiceProvider`. Windows implementation remains
future work, but it is not considered platform-impossible.

### 10. Share Apple code in appleMain

The iOS and macOS variants will share their `CBPeripheralManager` wrapper, GATT database mapping,
session registry, request translation, subscription tracking, notification handling, and
backpressure state in `appleMain` (or the repository's equivalent shared Darwin source set).

`iosMain` and `macosMain` will contain only lifecycle, restoration, entitlement, or availability
differences that cannot be expressed in the shared source set.

The application supplies a stable restoration identifier when restoration is enabled. Blue Falcon
will not silently generate an identifier whose continuity the application cannot control.

On iOS, restoration-enabled applications must instantiate `BlueFalconPeripheral` during early
application bootstrap with the same persisted restoration identifier on every launch. It must not
be created lazily from a view model, screen, or feature-scoped dependency graph. The integration
documentation will show initialization from `application(_:didFinishLaunchingWithOptions:)` and
the equivalent SwiftUI or scene-based startup path, before UI-driven common code is initialized.
Scene-based applications must persist and reuse the identifier themselves rather than depending on
launch options to supply it.

Core Bluetooth relaunch eligibility remains an operating-system policy rather than a library
guarantee. In particular, Apple documents additional AccessorySetupKit requirements for Bluetooth
restoration relaunch on iOS and iPadOS 26 and later. The `state restoration` capability means that
the backend supports stable manager reconstruction and restoration callbacks when the operating
system makes them available; it does not promise that every application will be relaunched.

### 11. Define concurrency and ownership rules

- The manager owns its coroutine scope and platform delegate.
- Platform callbacks are serialized through a manager-owned dispatcher.
- Application code is not invoked directly on a Bluetooth or main-thread callback.
- Every inbound and outbound `ByteArray` is copied at the public boundary.
- Mutable platform objects remain internal.
- Lifecycle operations are serialized.
- A manager and all of its sessions are safe to use from multiple coroutines.
- Central/client and peripheral/server managers may run simultaneously but share no mutable
  lifecycle or connection state.

## Consequences

### Positive

- Client-only applications retain a small dependency footprint.
- Android, iOS, and macOS receive a common production-grade GATT server contract.
- Multi-central state and targeted delivery become explicit.
- Applications can implement strict bounded, priority, or persistent scheduling without fighting
  an invisible engine queue.
- Most applications can install the official queue plugin instead of writing serialization logic.
- Structured session ownership reduces leaked callbacks, GATT servers, and coroutine work.
- Apple code is implemented once for iOS and macOS.
- Windows and Linux can be added later without redesigning the common API.
- Unsupported targets fail predictably.

### Negative

- Moving the 3.5.0 API is a source-breaking next-major change.
- Existing users must update dependencies, imports, and advertiser calls.
- A production session model is larger than the current replay-oriented advertiser interface.
- Hardware-dependent multi-central and backpressure behavior cannot be fully verified by ordinary
  host unit tests.
- Linux cannot promise the same targeted-notification semantics as Android, Apple, and Windows.
- The queue plugin adds another artifact for consumers that want default scheduling.

### Neutral

- The broadcast plugin changes dependency and construction wiring but retains its application-level
  purpose.
- Platform lifecycle and permission setup remain partly application-owned.
- Windows and Linux appear in the capability roadmap but are not part of the first implementation
  milestone.

## Alternatives Considered

### Alternative 1: Keep peripheral APIs in blue-falcon-core

**Pros:**

- No dependency or import migration.
- Fewer published artifacts.

**Cons:**

- Every client-only consumer receives server-role APIs.
- Core must evolve with server platform requirements.
- Violates the lightweight core and optional-feature principles.

**Why not chosen:** It couples two distinct BLE roles and contradicts the maintainer-approved module
boundary.

### Alternative 2: Publish one peripheral artifact per platform

Examples would include `blue-falcon-peripheral-android`,
`blue-falcon-peripheral-apple`, and `blue-falcon-peripheral-windows`.

**Pros:**

- Smallest platform-specific artifacts.
- Independent platform release cycles.

**Cons:**

- More Gradle coordinates and publishing configuration.
- More complex setup for multiplatform consumers.
- The first milestone has only Android and Apple implementations, so the additional structure has
  little immediate value.

**Why not chosen:** A single KMP artifact is simpler and Gradle selects the appropriate platform
variant automatically.

### Alternative 3: Introduce a second session API and deprecate BluetoothAdvertiser later

**Pros:**

- Existing 3.5.0 code continues compiling.
- New work can begin without moving files first.

**Cons:**

- Creates two competing GATT server stacks.
- Duplicates fixes, tests, and platform ownership.
- Makes eventual migration harder.

**Why not chosen:** The maintainers explicitly approved moving and evolving the existing
implementation.

### Alternative 4: Hide all notification queueing in the engine

**Pros:**

- Simplest call site for basic applications.
- Engines can automatically serialize platform operations.

**Cons:**

- Cannot provide application-specific bounds, priorities, cancellation, or persistence.
- Makes overload and memory behavior invisible.
- Prevents applications from reacting precisely to `Busy`.

**Why not chosen:** The approved hybrid design exposes readiness in the engine and supplies an
optional default queue plugin.

## Implementation Notes

Implementation will proceed through reviewable PRs after this ADR is accepted:

1. **Module extraction:** create `blue-falcon-peripheral`, move the 3.5.0 primitives and
   Android/Apple implementations without intentional behavior changes, and update the broadcast
   plugin dependency.
2. **Common production API:** add manager/session/request/result/capability contracts, an internal
   fake backend, and common contract tests.
3. **Android backend:** implement multi-central sessions, subscription tracking, explicit ATT
   responses, prepared writes, targeted notify/indicate, per-session `onMtuChanged` tracking,
   notification completion, and deterministic teardown.
4. **Apple backend:** implement the shared `appleMain` manager, session/subscription registry,
   targeted updates, readiness, maximum update length, restoration hooks, and early-startup
   integration guidance.
5. **Queue plugin:** implement bounded FIFO scheduling, fairness, cancellation, and overflow
   behavior.
6. **Documentation and examples:** add the migration guide, simultaneous central/peripheral
   example, two-central example, iOS restoration bootstrap examples for UIKit and SwiftUI/scene
   applications, capability documentation, and future Windows/Linux issues.

Every PR must compile all published variants and must not introduce silent no-op paths.

Testing will include:

- common manager state-machine tests;
- session lifecycle, suspending terminal close, cancellation, and cleanup tests;
- one-shot response, fallback-before-delivery, timeout, and overflow tests;
- Android default-MTU, per-session MTU update, session reset, and oversize rejection tests;
- notification result/readiness contract tests;
- queue bounds, drain-until-busy, busy-to-readiness race, readiness wake-up, fairness,
  cancellation, and session-close tests;
- platform mapping tests behind wrapper interfaces;
- Android instrumented tests for the real GATT server where CI hardware permits;
- Apple native compile/tests and documented two-device manual scenarios;
- API/ABI validation and documentation checks.

The first implementation milestone is complete when Android, iOS, and macOS support:

- simultaneous central and peripheral managers;
- multiple observable central sessions;
- explicit application-controlled ATT responses;
- subscription tracking;
- targeted notification/indication;
- backpressure and readiness;
- maximum update length;
- deterministic stop and close behavior;
- the optional bounded queue plugin.

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)
- [ADR 0006: BLE Device Broadcast Plugin](0006-ble-device-broadcast-plugin.md) — partially
  superseded for peripheral module ownership and Windows capability

## References

- [Blue Falcon issue #231](https://github.com/Reedyuk/blue-falcon/issues/231)
- [Android BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)
- [Android BluetoothGattServerCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback)
- [Apple CBPeripheralManager](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager)
- [Apple peripheral manager restoration identifier](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanageroptionrestoreidentifierkey)
- [Apple Core Bluetooth background processing and restoration](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
- [Apple TN3115: Bluetooth state restoration app relaunch rules](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules)
- [Windows Bluetooth GATT Server](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-server)
- [BlueZ GATT API](https://bluez.readthedocs.io/en/latest/gatt-api/)
- [Web Bluetooth specification](https://webbluetoothcg.github.io/web-bluetooth/)

# Central/client production parity design

## Goal

Extend Blue Falcon's central/client API with the production primitives required by a bidirectional
peer-to-peer BLE transport:

- observable characteristic-write outcomes and backpressure;
- per-connection maximum write lengths;
- observable notification-subscription outcomes;
- Apple central restoration for iOS and macOS.

The first implementation targets Android, iOS, and native macOS. Other engines must report
unsupported capabilities explicitly until they implement the same contract.

This work is a prerequisite for applications such as bitchat to remove their direct
`BluetoothGatt` and `CoreBluetooth` client implementations. It does not move application
protocol, routing, reliability, or security policy into Blue Falcon.

## Context

ADR 0007 and the peripheral module make the GATT-server side suitable for production use:

- remote centrals are represented as sessions;
- ATT requests have one-shot responses and fallback deadlines;
- notification payload limits are session-scoped;
- notification backpressure is explicit;
- QueuePlugin provides an optional bounded sequential queue.

The existing central/client side does not yet expose equivalent guarantees.

`BlueFalcon.writeCharacteristic` currently returns `Unit` and wraps the engine call in
`runCatching`, so callers cannot distinguish a successful platform submission from a rejected
operation, disconnect, oversized payload, or platform failure. Apple delegate callbacks already
observe notification-state changes, but those outcomes are not exposed through the core API.
Apple write-without-response readiness and central restoration are also not represented.

An application can compensate only by adding its own Android and Apple delegates. That would
duplicate Blue Falcon's platform engines and defeat the purpose of adopting the library.

## Goals

- Return typed, portable outcomes for characteristic writes.
- Never silently convert a failed write into apparent success.
- Expose write-without-response backpressure without busy polling.
- Expose a maximum write length for each connected peripheral and write type.
- Confirm whether enabling or disabling notifications succeeded.
- Allow Apple applications to provide a stable central restoration identifier during early
  process startup.
- Deliver restored peripherals without declaring them application-ready prematurely.
- Preserve the lightweight common core and the existing engine/plugin architecture.
- Keep the API implementable on Windows, Linux, JavaScript, and other engines later.
- Maintain source compatibility where practical while making unsupported behavior explicit.

## Non-goals

Blue Falcon will not own:

- application framing or fragmentation;
- acknowledgements or end-to-end delivery guarantees;
- peer identity, routing, deduplication, or redundant-link selection;
- durable queues or store-and-forward behavior;
- protocol-specific retry and backoff;
- encryption, authentication, or session negotiation;
- application background-execution policy;
- automatic reconnection of every restored peripheral;
- a central-side durable queue plugin in this change.

The peripheral QueuePlugin remains scoped to GATT-server notifications. Applications may build a
central-side queue above the low-level write result and readiness contracts if they need one.

## Approach

Add a small set of typed central capability and event contracts to `blue-falcon-core`. Engines
translate the strongest guarantees their platform exposes into those contracts:

- Android uses GATT callbacks and negotiated MTU state.
- Apple uses CoreBluetooth write readiness, maximum write length, notification-state callbacks,
  and restoration callbacks.
- engines without an implementation report `Unsupported`; they must not return a synthetic
  success result.

The work is delivered as stacked pull requests:

1. reliable central writes, write readiness, write limits, and subscription outcomes for common,
   Android, iOS, and macOS;
2. Apple central restoration and early-startup documentation;
3. focused examples and final migration documentation if those would make either implementation PR
   too large.

Each implementation PR is vertically complete for the behavior it introduces.

## Common API

### Write type

Replace raw integer write-type selection in the new API with an explicit common type:

```kotlin
enum class CharacteristicWriteType {
    WithResponse,
    WithoutResponse,
}
```

Existing overloads that accept `Int?` may remain temporarily as compatibility wrappers. They map
to the typed overload and are deprecated only if that can be done without forcing an unrelated
major-version migration.

### Write result

The typed write operation returns:

```kotlin
sealed interface CharacteristicWriteResult {
    data object Sent : CharacteristicWriteResult
    data object Backpressured : CharacteristicWriteResult

    data class PayloadTooLarge(
        val maximumLength: Int,
    ) : CharacteristicWriteResult

    data object Disconnected : CharacteristicWriteResult
    data object Unsupported : CharacteristicWriteResult

    data class Failed(
        val cause: Throwable?,
    ) : CharacteristicWriteResult
}
```

The new operation follows this shape:

```kotlin
suspend fun writeCharacteristic(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray,
    writeType: CharacteristicWriteType = CharacteristicWriteType.WithoutResponse,
): CharacteristicWriteResult
```

`Sent` means that the local platform accepted or completed the write at the strongest completion
level available for the selected write type:

- a write with response completes only after its authoritative platform callback;
- a write without response may complete when the platform accepts it because ATT provides no
  remote response for that operation.

`Sent` is not an end-to-end application acknowledgement.

`Backpressured` means that the value was not accepted. The caller must wait for a later readiness
signal before retrying. Blue Falcon must not retain a hidden unbounded payload queue after returning
this result.

`PayloadTooLarge` is decided before invoking the platform write API whenever the current maximum is
known. Engines must not depend on platform-specific silent truncation.

`Disconnected` covers a peripheral or characteristic that is no longer usable because its
connection ended. Invalid caller-owned objects and other platform failures return `Failed`.

Cancellation of the suspending operation cancels the Blue Falcon waiter. A late platform callback
must not resume the cancelled continuation or complete a newer operation.

### Write capability

The engine exposes connection-scoped write capability:

```kotlin
data class CharacteristicWriteCapability(
    val peripheralUuid: String,
    val writeType: CharacteristicWriteType,
    val maximumLength: Int?,
    val ready: Boolean,
    val supported: Boolean,
)
```

The public surface provides a durable snapshot plus edge-triggered readiness:

```kotlin
val characteristicWriteCapabilities:
    StateFlow<Map<String, Set<CharacteristicWriteCapability>>>

val characteristicWriteReady: Flow<CharacteristicWriteReady>
```

The precise collection shape may be simplified during implementation if the existing Blue Falcon
model has a more natural keyed representation. The required semantics are:

- capability is scoped to one connected peripheral and one write type;
- the map key uses the existing `BluetoothPeripheral.uuid`;
- the latest maximum and readiness are readable without waiting for a transient event;
- readiness events are hints to retry, not reservations of a platform slot;
- disconnect removes that peripheral's capability state;
- reconnect creates a new capability epoch so stale callbacks cannot mutate the new connection.

The convenience query:

```kotlin
fun maximumWriteValueLength(
    peripheral: BluetoothPeripheral,
    writeType: CharacteristicWriteType,
): Int?
```

returns the latest known value. `null` means unknown, not unlimited.

### Notification subscription result

Enabling or disabling notifications returns a typed result:

```kotlin
sealed interface NotificationSubscriptionResult {
    data class Updated(val enabled: Boolean) : NotificationSubscriptionResult
    data object Disconnected : NotificationSubscriptionResult
    data object Unsupported : NotificationSubscriptionResult
    data class Failed(val cause: Throwable?) : NotificationSubscriptionResult
}
```

The operation completes after the authoritative platform outcome rather than after merely invoking
the platform setter:

```kotlin
suspend fun setNotificationSubscription(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    enabled: Boolean,
): NotificationSubscriptionResult
```

An event flow may additionally expose externally initiated or restored subscription changes:

```kotlin
val notificationSubscriptionUpdates: Flow<NotificationSubscriptionUpdate>
```

Applications must not have to infer subscription success from the first received notification.

### Capabilities

The core exposes whether an engine implements the new behavior:

```kotlin
data class CentralCapabilities(
    val reliableWriteResults: Boolean,
    val writeWithoutResponseReadiness: Boolean,
    val perConnectionMaximumWriteLength: Boolean,
    val notificationSubscriptionResults: Boolean,
    val restoration: Boolean,
)
```

Unsupported engines return false and typed `Unsupported` outcomes. They do not pretend that a
write, subscription, or restoration operation succeeded.

## Operation ownership and stale callbacks

Every pending platform operation is owned by:

- a connected peripheral;
- the target characteristic where applicable;
- the operation type;
- a monotonically increasing connection or operation generation.

At most one callback-owned write-with-response operation may be pending for a platform resource
that cannot disambiguate concurrent callbacks. Implementations may serialize this minimum platform
requirement internally, but must not turn it into an unbounded application payload queue.

On disconnect, close, or engine replacement:

- pending operations complete as `Disconnected` or cancellation, as appropriate;
- capability state for the old connection is removed;
- late callbacks are ignored using generation ownership;
- a restored or reconnected peripheral starts a new generation.

This prevents a callback from an old physical connection from completing an operation on a newer
connection that happens to use the same peripheral identifier.

## Android implementation

### Writes

The Android engine validates:

- the peripheral is connected;
- the characteristic belongs to the active connection;
- the characteristic supports the selected write type;
- the payload does not exceed the latest known limit.

For a write with response, `Sent` is returned after the matching `onCharacteristicWrite` success
callback. A non-success GATT status returns `Failed`; disconnect completes it as `Disconnected`.

For a write without response, the implementation reports the strongest reliable Android outcome.
If the SDK/device supplies a matching completion callback, it is used. If Android only confirms
local submission for the selected path, `Sent` means local acceptance and this weaker meaning is
documented. Immediate `BluetoothGatt.writeCharacteristic` rejection returns `Backpressured` only
when the engine can deterministically emit a later readiness transition. Otherwise it returns
`Failed`; callers must never be told to wait for an event that cannot occur.

The implementation must account for SDK differences between the legacy mutable-characteristic API
and the newer write overload.

### Maximum length

Before MTU negotiation, the default ATT payload limit is 20 bytes. On `onMtuChanged`, the engine
updates only that connection:

```text
maximum write payload = negotiated MTU - 3-byte ATT header
```

The negotiated value is discarded at disconnect. It is never shared across peripherals.

If a platform or write mode imposes a smaller authoritative limit, the smaller limit wins.

### Subscription

The subscription operation owns both local notification configuration and the required CCCD write.
It completes only after the matching platform callback establishes success or failure. Disconnect
and cancellation clear pending ownership. A stale descriptor callback cannot complete a later
subscription operation.

## Apple implementation

iOS and native macOS share their CoreBluetooth implementation through a new shared Apple/Darwin
source-set boundary wherever their APIs are equivalent. Target source sets retain only genuinely
different construction, lifecycle, or restoration hooks.

### Writes and readiness

For `WithoutResponse`, the engine checks
`CBPeripheral.canSendWriteWithoutResponse` immediately:

- if true, it submits the write and returns `Sent`;
- if false, it returns `Backpressured` without retaining the payload.

`peripheralIsReadyToSendWriteWithoutResponse` updates durable readiness state and emits a
`characteristicWriteReady` hint. A caller should attempt writes in a tight loop while results are
`Sent`, then suspend only after receiving `Backpressured`.

For `WithResponse`, the operation completes from `didWriteValueForCharacteristic`. Errors and
disconnects become typed results.

The maximum is read through `maximumWriteValueLengthForType` for the selected CoreBluetooth write
type. The implementation must not use the with-response value as a substitute for a
without-response limit.

### Subscription

`setNotifyValue` completion is owned by
`didUpdateNotificationStateForCharacteristic`. The engine verifies both the callback error and the
characteristic's resulting notification state before returning `Updated`.

The existing delegate callback must no longer terminate in a no-op implementation.

## Apple central restoration

### Configuration

Apple central construction accepts an optional stable restoration identifier:

```kotlin
data class AppleCentralConfig(
    val restorationIdentifier: String? = null,
)
```

When provided, the engine creates `CBCentralManager` with the corresponding restoration option.
Applications that opt into restoration must create the Blue Falcon central runtime during early
application startup, not lazily from a screen or ViewModel.

The central and peripheral restoration identifiers are separate because CoreBluetooth restores
their managers independently.

### Restored state

The Apple delegate handles `centralManager(_:willRestoreState:)` and converts the available native
state into a common event:

```kotlin
data class RestoredCentralState(
    val peripherals: List<BluetoothPeripheral>,
)
```

The common event does not claim that restored peripherals are application-ready. The application
must reconcile each peripheral by checking connection state, discovering the required services and
characteristics where needed, confirming notification subscription, and reading current write
capability.

Restoration callbacks may arrive before a UI collector exists. The latest unconsumed restoration
state therefore needs durable, bounded ownership rather than a transient `SharedFlow` with no
replay. Consumption/acknowledgement semantics must prevent both silent loss and unbounded replay.

If no restoration identifier is configured, restoration capability is false and no restoration
state is fabricated.

### Lifecycle

The engine remains process-owned:

- ordinary application stop or screen disposal stops scan/connect work but does not recreate the
  restoration manager;
- terminal `close` releases engine-owned delegates and pending operations;
- restoration state received for a closed engine is ignored;
- initialization documentation shows early startup for iOS and macOS.

## Error and cancellation semantics

- Public methods must not use `runCatching` in a way that discards failures.
- Expected transport outcomes use typed results.
- Programmer errors and invalid object ownership may use `Failed` with a cause rather than crash a
  callback thread.
- A cancelled call releases its pending-operation slot.
- Platform delegate callbacks never block waiting for application coroutines.
- Event delivery is bounded and cannot stall the platform callback queue.
- Closing an engine deterministically completes or cancels every pending operation.
- No implementation uses `runBlocking` or fire-and-forget cleanup to satisfy a suspending lifecycle
  contract.

## Compatibility

Existing call sites using the current `Unit`-returning API must be evaluated before changing the
signature directly. The preferred outcome is one canonical typed operation. If binary or source
compatibility requires a transition:

- retain the old overload as a deprecated wrapper;
- delegate it to the typed operation;
- do not translate `Failed`, `Disconnected`, `Unsupported`, or `PayloadTooLarge` into silent
  success;
- document the migration path and removal version.

Plugin interception must preserve the typed result. An interceptor cannot swallow an engine
failure and return apparent success.

## Testing

### Common contract tests

Fake-engine tests cover:

- every write result;
- with-response completion;
- without-response `Sent` and `Backpressured`;
- readiness snapshot and event handoff;
- per-peripheral, per-write-type maximum lengths;
- capability removal on disconnect;
- subscription success, failure, disconnect, and cancellation;
- stale write and subscription callbacks;
- unsupported engine behavior;
- plugin interception without result loss;
- restored state delivery and bounded consumption;
- close with pending operations.

### Android tests

Tests drive the engine's callback boundary and verify:

- the default 20-byte payload limit;
- connection-scoped `onMtuChanged`;
- immediate write rejection;
- successful and failed `onCharacteristicWrite`;
- disconnect while a write is pending;
- CCCD subscription success and failure;
- stale callback rejection;
- cleanup of pending operations.

### Apple tests

Tests isolate the delegate/backend boundary and verify:

- `canSendWriteWithoutResponse` fast-path and backpressure;
- readiness transition from
  `peripheralIsReadyToSendWriteWithoutResponse`;
- distinct with-response and without-response maximum lengths;
- `didWriteValueForCharacteristic` success and error;
- `didUpdateNotificationStateForCharacteristic` success and error;
- restoration configuration;
- early restored-state delivery;
- reconnect generation and stale callback rejection.

Verification compiles both iOS and native macOS implementations.

### Manual hardware matrix

Before declaring the APIs production-ready:

| Central | Peripheral | Required behavior |
| --- | --- | --- |
| Android | Android | write outcomes, MTU change, subscription, reconnect |
| Android | iOS | bidirectional traffic, fragmentation boundary, reconnect |
| iOS | Android | write backpressure and notification subscription |
| iOS | iOS | background/foreground and reconnect |
| macOS | Android/iOS | central writes and subscriptions |
| iOS | Android/iOS | process restoration and state reconciliation |

The matrix also exercises Bluetooth off/on, permission loss, out-of-range disconnect, rapid
reconnect, and sustained payload transmission.

## Documentation and examples

Documentation must explain:

- exact `Sent` semantics for each platform and write type;
- that readiness is a retry hint, not a reservation;
- that `null` maximum length means unknown;
- that fragmentation remains application-owned;
- that a subscription is ready only after its typed success result;
- Apple early initialization and stable restoration identifiers;
- restored peripherals require application reconciliation;
- unsupported targets report unsupported behavior explicitly.

A focused example should attempt writes until `Backpressured`, wait for readiness, and resume
without busy polling. It should display typed failures rather than hide them.

## Acceptance criteria

- `writeCharacteristic` has a typed path whose failures cannot be silently swallowed.
- Android, iOS, and native macOS expose the strongest reliable write outcome available.
- Apple write-without-response backpressure is observable and resumable.
- Maximum write length is scoped to one peripheral and write type.
- Android updates the limit from connection-specific MTU callbacks.
- Apple queries the correct CoreBluetooth write type.
- Notification subscription success and failure are observable.
- Apple central restoration uses a stable application-provided identifier.
- Restored peripherals are delivered durably but are not declared ready automatically.
- Cancellation, disconnect, close, and stale callbacks have deterministic behavior.
- Unsupported targets never return synthetic success.
- Common, Android, iOS, and macOS verification passes.
- Documentation clearly separates Blue Falcon transport responsibilities from application
  protocol and reliability responsibilities.

## Downstream migration boundary

Once these contracts are merged and available from a Blue Falcon version or an explicitly pinned
commit, bitchat can:

- consume Blue Falcon directly from its common BLE bearer;
- use `BlueFalconPeripheral` and QueuePlugin for the server role;
- wait for typed central subscription and write readiness;
- adapt fragmentation to each link's maximum write length;
- restore Apple central and peripheral managers during early startup;
- delete its Android `BluetoothGatt` and Apple `CoreBluetooth` GATT managers.

bitchat retains packet framing, fragmentation policy, Noise, routing, redundant-link policy,
retry, deduplication, and durable spool behavior. No runtime legacy/Blue-Falcon feature flag is
part of the final migration.

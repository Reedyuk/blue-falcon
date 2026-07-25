# Peripheral Queue Plugin Design

## Goal

Provide an optional, bounded, fair notification queue for `BlueFalconPeripheral` sessions without
moving application transport policy into platform backends. The plugin accepts complete ATT payloads
only; message framing, fragmentation, acknowledgement, retry, and persistence remain application
responsibilities.

## Scope

This change adds:

- a small peripheral-specific plugin lifecycle API in `blue-falcon-peripheral`;
- the multiplatform `blue-falcon-plugin-queue` artifact;
- a `QueuePlugin` that queues targeted notifications and indications across sessions.

It does not change `BlueFalconPlugin`, central/client APIs, Android or Apple backend notification
semantics, or the legacy advertiser façade.

## Public API

`BlueFalconPeripheral` exposes a `PeripheralPluginRegistry`. A registry installs one configured
plugin instance and closes all installed plugins during `BlueFalconPeripheral.close()`.

```kotlin
val queue = peripheral.plugins.install(QueuePlugin) {
    maxPendingItemsPerSession = 64
    maxPendingBytes = 64 * 1024
    overflowPolicy = QueueOverflowPolicy.RejectNewest
}

when (queue.send(session, characteristic, payload)) {
    QueueSendResult.Sent -> Unit
    QueueSendResult.QueueFull -> retryLater()
    QueueSendResult.PayloadTooLarge -> splitAtTransportLayer()
    else -> handleSessionResult()
}
```

`QueuePlugin` is a factory rather than a singleton queue. Its configuration is copied when it is
installed, so later mutation of the DSL object cannot alter a live scheduler. The returned
`PeripheralQueue` provides:

```kotlin
suspend fun send(
    session: PeripheralSession,
    characteristic: GattCharacteristicId,
    value: ByteArray,
    mode: NotificationMode = NotificationMode.Notification,
): QueueSendResult
```

`QueueSendResult` has `Sent`, `QueueFull`, `PayloadTooLarge`, `Disconnected`, `Unsupported`, and
`Failed(cause)` variants. `send` defensively copies its payload before it enters the queue.

`notificationReadiness` remains a bounded, non-blocking hint stream for application observers.
`notificationReadinessState` exposes durable manager and active-session epochs in a `StateFlow`.
QueuePlugin consumes the epoch state so a slow unrelated observer cannot drop its wake-up or
backpressure the manager's backend-event processor.

## Plugin Lifecycle

`PeripheralPlugin` receives the owning `BlueFalconPeripheral` and a child `CoroutineScope` when
installed. `DefaultBlueFalconPeripheral` owns that scope. It cancels the scope and calls every
plugin's suspend `close()` exactly once after the backend has stopped accepting work and before the
manager close operation completes. Installing the same plugin factory twice is rejected; this keeps
plugin lookup and ownership unambiguous.

The registry is intentionally peripheral-only. It resembles the existing client plugin registry
without placing GATT-server types in `blue-falcon-core` or adding client interceptor callbacks that
do not apply to a server.

## Queue Data Model and Limits

The queue holds a copied payload, its session ID, characteristic ID, notification mode, byte count,
and a completion deferred. A single mutex protects:

- one FIFO deque for each session;
- an ordered round-robin ring of session IDs with pending work;
- total queued byte count;
- total queued item count and each session's queued byte count;
- blocked session readiness epochs.

The default policy is `RejectNewest`. Enqueue returns `QueueFull` when either the per-session item
limit or the total byte limit would be exceeded; it does not evict an older item. All limits must be
positive. A known `session.maximumUpdateValueLength` smaller than the supplied value returns
`PayloadTooLarge` before the value is queued. If the platform has not exposed a limit yet (`null`),
the plugin submits the complete value and preserves the result reported by `session.notify`.
An empty payload consumes one accounting unit from the total byte budget so queued object metadata
cannot become globally unbounded across many sessions.

No payload is split. A zero maximum permits only an empty payload.

## Scheduling and Backpressure

One scheduler coroutine drains queues. During each round-robin pass it attempts no more than one
item from each eligible session. A `Sent` result completes that item and immediately begins the next
pass. This allows high-throughput platforms, including CoreBluetooth, to consume their acceptance
window without a `Flow` collection suspension between packets, while a continuously writable session
cannot starve another session.

When `notify` returns `Busy`, the item remains at the head of its FIFO and that session is marked
blocked. The manager records every readiness callback in durable monotonically increasing manager
or active-session epochs. A permanent collector observes `notificationReadinessState`. The
scheduler reads the relevant epoch before calling `notify`; after `Busy`, it rechecks the epoch under
the queue mutex. If it has already advanced, the session is retried in the next pass. Otherwise the
scheduler suspends until a matching epoch advances. This makes the handoff from `Busy` to waiting
loss-free without treating readiness as a reserved platform write slot or allowing a slow public
hint-flow collector to stall backend events.

`Disconnected`, `Unsupported`, and `Failed` complete only the submitted head item with the matching
typed result. `Disconnected` also removes the rest of that session queue. A session that disappears
from `peripheral.sessions` has all of its pending items completed as `Disconnected`. Stopping or
closing the peripheral completes every pending item as `Disconnected` and cancels scheduler work.

If a caller cancels its `send` coroutine before submission, its item is removed from the deque and
its bytes are released. Cancellation after platform submission does not retract an already accepted
ATT update.

## Testing

Common tests use controllable fake `PeripheralSession` and `BlueFalconPeripheral` contracts. They
verify:

- registry installation, duplicate rejection, and close ownership;
- payload defensive copying and all configuration limit validation;
- FIFO order within one session and round-robin order across sessions;
- immediate continued draining after `Sent`;
- `Busy` waits for matching readiness and does not resume for an unrelated session;
- readiness arriving between `Busy` and suspension causes an immediate retry;
- `PayloadTooLarge`, `QueueFull`, `Disconnected`, `Unsupported`, and failures;
- cancellation before submission and cleanup after session disappearance or peripheral close.

Backend-specific tests are not required for this artifact: Android and Apple already expose and test
the common `PeripheralSession.notify`, `notificationReadiness`, and maximum-length contracts that the
plugin consumes.

## Acceptance Criteria

- The queue module compiles for every target supported by `peripheral`.
- No unbounded queue, silent drop, payload fragmentation, or application delivery guarantee is
  introduced.
- A successful notification path does not await a readiness `Flow` between consecutive `Sent`
  results.
- Every queued caller eventually receives one typed terminal result or cancellation.

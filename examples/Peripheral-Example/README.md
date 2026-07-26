# Peripheral Echo Server Example

This standalone common Kotlin example hosts a small GATT echo server with the production
`BlueFalconPeripheral` API. It demonstrates application-owned lifecycle, explicit ATT request
routing, and notification backpressure through `QueuePlugin`.

The current peripheral backends support Android, iOS, and macOS. Other Blue Falcon targets can add
peripheral backends later; this example does not claim JVM server support.

## Dependencies

Use matching versions of the peripheral module and queue plugin in `commonMain`:

```kotlin
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-peripheral:<blue-falcon-version>")
    implementation("dev.bluefalcon:blue-falcon-plugin-queue:<blue-falcon-version>")
}
```

Copy [`src/PeripheralEchoServer.kt`](src/PeripheralEchoServer.kt) into your common source set, then
create exactly one server for each application-owned peripheral manager:

```kotlin
// Android application startup
val peripheral = createBlueFalconPeripheral(applicationContext)
val server = PeripheralEchoServer(peripheral, applicationScope)

applicationScope.launch {
    server.start()
}
```

```kotlin
// iOS/macOS application startup
val peripheral = createBlueFalconPeripheral()
val server = PeripheralEchoServer(peripheral, applicationScope)

applicationScope.launch {
    server.start()
}
```

The platform factory imports are:

```kotlin
import dev.bluefalcon.peripheral.android.createBlueFalconPeripheral // Android
import dev.bluefalcon.peripheral.apple.createBlueFalconPeripheral  // iOS/macOS
```

## Platform startup and ownership

### Android

Declare `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT`. Add `BLUETOOTH_SCAN` only when the
application also acts as a BLE client and scans for remote devices. Request the permissions required
by the Android version at runtime.

Create the manager with the application context. If the server must remain available while the app
is not visible, own its lifecycle from a foreground service and follow Android's background
execution and foreground-service restrictions. Do not leave it owned by a screen or short-lived
view model.

### Apple

Create the peripheral manager and launch `server.start()` during
`application(_:didFinishLaunchingWithOptions:)` or equivalent early application startup.
Constructing the factory alone is insufficient: the CoreBluetooth peripheral manager and its
restoration options are opened by `start()`. CoreBluetooth restoration requires this to happen
immediately with the same stable restoration identifier:

```kotlin
const val restorationIdentifier = "dev.bluefalcon.example.echo-peripheral"
```

Do not wait for lazy UI or view-model initialization when restoration is required. Keep the
application-owned manager, scope, and `PeripheralEchoServer` alive for the application's BLE
lifetime on both iOS and macOS.

## Exercise the server

The server advertises as `Blue Falcon Echo` and exposes:

- Service: `84f7e120-63fd-4f79-8b08-5b9780a36a94`
- Characteristic: `84f7e121-63fd-4f79-8b08-5b9780a36a94`
- Properties: read, write, write without response, notify, and indicate
- Initial value: `Hello from Blue Falcon`

Using a BLE client:

1. Connect to `Blue Falcon Echo` and discover the service and characteristic above.
2. Write bytes to the characteristic. A non-zero offset overlays the current value.
3. Read the characteristic to receive the updated echo value.
4. Subscribe to notifications or indications.
5. From the application, call `server.notifySubscribers(payload)`.

`notifySubscribers` returns one typed result per subscribed session:

- `QueueSendResult.Sent`
- `QueueSendResult.QueueFull`
- `QueueSendResult.PayloadTooLarge`
- `QueueSendResult.Disconnected`
- `QueueSendResult.Unsupported`
- `QueueSendResult.Failed(cause)`

The server snapshots the currently subscribed sessions and enqueues for them concurrently, so one
slow peer does not prevent the other peers from being offered the payload.

## Lifecycle and transport boundary

`start()` can follow `stop()`; stopping advertising and the GATT server is restartable. `close()` is
terminal and idempotent: it cancels the request collector and closes the peripheral manager. Calls
to `start()` or `stop()` after `close()` are rejected; create a new manager and server instead.

The caller-provided scope must be active at construction and must outlive the server. Call
`server.close()` before cancelling `applicationScope`. Cancelling the scope stops request routing,
and a later `start()` is rejected rather than advertising without a request handler.

Lifecycle operations are serialized. `notifySubscribers` does not hold the lifecycle lock; sends
racing with `close()` complete with the queue's typed result, including `Disconnected` or
`Failed(cause)`.

QueuePlugin provides bounded per-session queuing and platform-readiness handling. It intentionally
does not implement application-layer fragmentation, acknowledgements, retries, or persistence.
The stored echo attribute is limited to 512 bytes, and larger writes receive
`InvalidAttributeValueLength` without changing the stored value. This attribute limit is separate
from notifications: payloads passed to `notifySubscribers` must also fit each individual session's
`maximumUpdateValueLength`; larger notification payloads produce `PayloadTooLarge`.

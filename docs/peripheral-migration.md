# Migrating Peripheral APIs to `blue-falcon-peripheral`

ADR 0007 moves the experimental 3.5.x peripheral-role API out of the central/client artifacts for
the next major Blue Falcon release. Client-only applications continue to depend on
`blue-falcon-core`; applications that advertise or host a local GATT database must add:

```kotlin
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-peripheral:<version>")
}
```

Peripheral declarations now use the `dev.bluefalcon.peripheral` package:

```kotlin
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattServiceConfig
```

Android construction no longer extends `AndroidEngine`. Pass the application context directly:

```kotlin
import dev.bluefalcon.peripheral.android.createBluetoothAdvertiser

val advertiser = createBluetoothAdvertiser(applicationContext, logger)
```

iOS and macOS share the production CoreBluetooth manager and factory from `appleMain`:

```kotlin
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.apple.createBlueFalconPeripheral

val peripheral = createBlueFalconPeripheral(logger)
val config = PeripheralConfig(
    advertiseConfig = advertiseConfig,
    restorationIdentifier = persistedRestorationIdentifier,
)
applicationScope.launch {
    peripheral.start(config)
}
```

When `restorationIdentifier` is set on iOS, create and start the manager during early application
startup. UIKit applications must do this from
`application(_:didFinishLaunchingWithOptions:)`. SwiftUI and scene-based applications must create
the owning dependency before any view model can lazily request it. Persist and reuse the exact same
non-blank restoration identifier across launches; scene launch options are not a reliable source
for it. Initializing the manager later from a screen or view model prevents Core Bluetooth from
handing the restored peripheral state back to Blue Falcon.

The application owns dynamic characteristic reads and writes through `peripheral.requests`.
Core Bluetooth may deliver one write callback containing several characteristic writes. Blue
Falcon preserves that callback as one atomic `GattCharacteristicWriteBatchRequest`: validate and
apply every operation together, then call its response handle exactly once.

```kotlin
peripheral.requests.collect { request ->
    when (request) {
        is GattCharacteristicReadRequest -> {
            request.response.respond(GattResponseStatus.Success, readValue(request))
        }

        is GattCharacteristicWriteRequest -> {
            applyWrite(request)
            request.response?.respond(GattResponseStatus.Success)
        }

        is GattCharacteristicWriteBatchRequest -> {
            if (canApplyAtomically(request.writes)) {
                applyAtomically(request.writes)
                request.response.respond(GattResponseStatus.Success)
            } else {
                request.response.respond(GattResponseStatus.UnlikelyError)
            }
        }

        else -> request.response?.respond(GattResponseStatus.RequestNotSupported)
    }
}
```

Apple notification calls are targeted to the selected `PeripheralSession`. Core Bluetooth does not
report whether a central selected notification or indication when a characteristic supports both;
Blue Falcon validates the requested `NotificationMode` against the characteristic configuration,
while Core Bluetooth controls the actual ATT delivery mode from the central's subscription.
`NotificationReadiness.Manager` is manager-wide because Core Bluetooth does not identify which
central released transmit capacity.

`notificationReadiness` is a bounded hint stream intended for lightweight application observation;
slow collectors do not backpressure platform callbacks. Plugins that require a loss-free handoff
use `notificationReadinessState`, whose manager and active-session epochs remain durable even when
the hint stream coalesces under load.

The old `createBluetoothAdvertiser(logger)` and `AppleBluetoothAdvertiser` APIs remain as deprecated
compatibility façades over the production manager. New code should use
`createBlueFalconPeripheral(logger)` directly.

The broadcast plugin now exposes `blue-falcon-peripheral` transitively, but applications that use
the peripheral API directly should declare the dependency explicitly.

Applications that need bounded notification serialization can add the optional queue plugin:

```kotlin
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-plugin-queue:<version>")
}
```

Install it once on the peripheral manager and use the returned queue for complete ATT values:

```kotlin
import dev.bluefalcon.plugins.queue.QueuePlugin

val queue = peripheral.plugins.install(QueuePlugin) {
    maxPendingItemsPerSession = 64
    maxPendingBytes = 64 * 1024
}

val result = queue.send(
    session = session,
    characteristic = characteristicId,
    value = encodedAttValue,
)
```

The queue preserves FIFO order per session, schedules active sessions round-robin, and resumes
blocked sends from the durable `notificationReadinessState`. It rejects the newest value with
`QueueFull` when either configured bound is reached. A value larger than a session's known
`maximumUpdateValueLength` returns `PayloadTooLarge` without reaching the platform backend.
Empty values reserve one unit of the total byte budget so metadata-only queue entries remain
globally bounded.

`QueuePlugin` deliberately does not fragment messages, add transport acknowledgements, persist
pending data, reconnect sessions, or retry terminal failures. Protocol framing, fragmentation,
ACK/retry policy, and durable delivery remain application transport responsibilities.

The peripheral module now provides the production manager/session contract, application-controlled
ATT responses, targeted multi-central updates, per-session maximum update lengths,
readiness/backpressure signals, and opt-in Apple restoration. Queue policy remains an explicit
application or plugin concern rather than a hidden platform retry queue.

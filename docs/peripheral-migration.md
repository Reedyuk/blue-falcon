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

iOS and macOS share the same CoreBluetooth implementation and factory from `appleMain`:

```kotlin
import dev.bluefalcon.peripheral.apple.createBluetoothAdvertiser

val advertiser = createBluetoothAdvertiser(logger)
```

The broadcast plugin now exposes `blue-falcon-peripheral` transitively, but applications that use
the peripheral API directly should declare the dependency explicitly.

This extraction preserves the 3.5.x advertiser behavior. It does not yet add the production
manager/session contract, application-controlled ATT responses, targeted multi-central updates,
MTU tracking, readiness/backpressure, restoration, or the bounded queue plugin. Those changes are
separate implementation stages of ADR 0007.

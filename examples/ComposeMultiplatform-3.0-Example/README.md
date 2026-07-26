# Compose Multiplatform Example - Blue Falcon 3.0

This application demonstrates both BLE roles exposed by Blue Falcon:

- **Central** scans, connects, discovers services, reads and writes characteristics, subscribes to
  notifications, changes MTU, reads RSSI, performs Nordic FOTA, and exercises the existing clone
  and broadcast plugins.
- **Peripheral** hosts an interactive echo GATT server on Android, iOS, and Kotlin/Native macOS.

Use the **Central / Peripheral** segmented selector at the top of the application to switch modes.
The central workflow is unchanged by the peripheral example.

## Peripheral echo mode

The server advertises as `Blue Falcon Echo` and exposes one characteristic:

- Service: `84f7e120-63fd-4f79-8b08-5b9780a36a94`
- Characteristic: `84f7e121-63fd-4f79-8b08-5b9780a36a94`
- Properties: read, write, write without response, notify, and indicate
- Initial value: `Hello from Blue Falcon`
- Stable Apple restoration identifier: `dev.bluefalcon.example.echo-peripheral`

The screen displays manager state, connected-session count, subscribed-session count, Start and
Stop controls, an editable notification payload, a Send button, and a bounded activity log. Start
opens the GATT server and begins advertising. Stop is restartable. Send snapshots the sessions
currently subscribed to the echo characteristic and offers the payload to all of them concurrently,
so one slow session does not block the other sessions.

Reads return the stored echo bytes from the requested offset. Writes overlay the stored value at the
requested offset, up to the 512-byte GATT attribute limit. A response-required write is staged and
committed only after its response handle returns `GattResponseResult.Responded`; an expired or
already-completed response leaves the stored value unchanged. A write without response has no
response handle and commits immediately after validation. Invalid offsets, oversized values,
unknown handles, prepared writes, batches, descriptor requests, and execute-write requests receive
explicit ATT status handling.

## QueuePlugin behavior

Peripheral mode installs exactly one `QueuePlugin` instance on its application-owned manager with
these limits:

```kotlin
maxPendingItemsPerSession = 64
maxPendingBytes = 64 * 1024
```

The plugin applies bounded per-session backpressure and platform notification-readiness handling.
The activity log keeps each session's typed outcome visible:

- `QueueSendResult.Sent`
- `QueueSendResult.QueueFull`
- `QueueSendResult.PayloadTooLarge`
- `QueueSendResult.Disconnected`
- `QueueSendResult.Unsupported`
- `QueueSendResult.Failed(cause)`

The example intentionally does not add application-layer fragmentation, acknowledgements, retry, or
persistence. A notification payload must fit each session's `maximumUpdateValueLength`; otherwise
that session reports `PayloadTooLarge`.

## Platform ownership and startup

### Android

The manifest declares `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, and the central-mode
`BLUETOOTH_SCAN` permission. Android 12 and newer require the applicable Bluetooth permissions at
runtime.

`BlueFalconApplication.onCreate()` creates one process-owned `AppModule`, and the activity reuses it.
This keeps the peripheral manager and QueuePlugin out of screen and view-model lifecycles. The
interactive demo still opens the GATT server only when the user presses Start. If a production
server must remain available while the UI is not visible, a foreground service must own its
desired-running state and BLE lifecycle under Android's background-execution rules.

### iOS and macOS

The iOS `AppDelegate` creates one application-owned `AppModule` during startup and passes it through
SwiftUI into Compose. A native macOS host should use the same application-owned pattern. Both
platforms use the Apple peripheral factory and the stable restoration identifier above.

The interactive demo does **not** automatically restore a previously running server. Creating the
factory wrapper or `AppModule` alone is insufficient because the CoreBluetooth peripheral manager
and restoration options are opened by `start()`, which remains user-triggered in this UI.

A production restoration flow must persist the desired-running state, create the application-owned
request router/server during `AppDelegate` startup, and call `start()` immediately with the same
restoration identifier before waiting for Compose, a view model, or another lazy UI path. The
[standalone Peripheral Echo Server example](../Peripheral-Example/) shows an
application-scope startup pattern, including `CoroutineStart.UNDISPATCHED`.

### JVM desktop

JVM desktop retains the central role through the platform-specific JVM engines, but
`peripheralRuntime` is deliberately `null`. Selecting Peripheral shows an explicit unsupported
message; the example does not claim a JVM GATT-server backend.

The shared source set also compiles a native macOS peripheral backend. This repository currently
provides Android, iOS, and JVM desktop runners, but no native macOS application runner.

## Build against the current branch

`shared/build.gradle.kts` defines one `falconVersion` for all Blue Falcon modules. The example
currently uses `3.6.1`. The peripheral and QueuePlugin changes on this branch are not yet guaranteed
to exist in a remote Maven repository, so publish those two matching artifacts to Maven Local
before resolving the example:

```bash
cd library
./gradlew \
  -PversionPeripheral=3.6.1 \
  -PversionPlugins=3.6.1 \
  :peripheral:publishToMavenLocal \
  :plugins:queue:publishToMavenLocal

cd ../examples/ComposeMultiplatform-3.0-Example
```

The example's `settings.gradle.kts` restricts Maven Local resolution to the `dev.bluefalcon` group,
so unrelated locally published Kotlin libraries do not override normal repository artifacts. When
the project version changes, update only `falconVersion` and publish the local modules with that
same version; do not assign different module versions in dependency snippets.

## Build and run

Use JDK 21 and configure the Android SDK for Android builds.

### Android

```bash
./gradlew :androidBlueFalconExampleMP:installDebug
```

Run on BLE hardware and grant the requested Bluetooth permissions.

### iOS

Build the Kotlin framework:

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

Then open `iosBlueFalconExampleMP/iosBlueFalconExampleMP.xcodeproj` in Xcode and run the iOS app.
BLE peripheral behavior should be exercised on supported hardware; simulator support is not a
substitute for device testing.

### JVM desktop

```bash
./gradlew :desktopBlueFalconExampleMP:run
```

Central mode selects the Windows, Linux, or macOS JVM engine at runtime. Peripheral mode is
unsupported as described above.

### Native macOS shared target

There is no native macOS runner in this example, but the shared integration can be compiled with:

```bash
./gradlew :shared:compileKotlinMacosArm64
```

## Project structure

```text
ComposeMultiplatform-3.0-Example/
├── shared/
│   └── src/
│       ├── commonMain/
│       │   ├── .../ble/             # Central presentation and operations
│       │   ├── .../peripheral/      # Echo runtime, controller, state, and UI
│       │   └── .../di/AppModule.kt  # Shared application dependencies
│       ├── androidMain/              # Android central and peripheral factories
│       ├── iosMain/                  # iOS central and Apple peripheral factories
│       ├── macosMain/                # Native macOS central and Apple peripheral factories
│       └── jvmMain/                  # Central engines; no peripheral backend
├── androidBlueFalconExampleMP/
├── desktopBlueFalconExampleMP/
└── iosBlueFalconExampleMP/
```

## Related documentation

- [Standalone Peripheral Echo Server](../Peripheral-Example/)
- [Migration guide](../../docs/MIGRATION_GUIDE.md)
- [Peripheral migration guide](../../docs/peripheral-migration.md)
- [Plugin development guide](../../docs/PLUGIN_DEVELOPMENT_GUIDE.md)

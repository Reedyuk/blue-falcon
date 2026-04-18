# Notification Example

This example demonstrates how to subscribe to BLE characteristic notifications using Blue Falcon 3.0's reactive API.

## What's Included

### NotificationExample.kt

Five progressively detailed examples showing every way to consume BLE notifications:

| # | Pattern | When to use |
|---|---------|-------------|
| 1 | **Per-characteristic `SharedFlow`** | Bind one UI element or coroutine pipeline to a single characteristic |
| 2 | **Engine-level `SharedFlow`** | Centralised packet router across all characteristics and peripherals |
| 3 | **Plugin `onNotificationReceived`** | Cross-cutting concerns – logging, metrics, protocol decoding |
| 4 | **Full wiring** | Scan → connect → discover → subscribe → observe → disconnect |
| 5 | **Heart-rate monitor** | Real-world byte-parsing with `Flow.map {}` |

## API Overview

### Per-characteristic Flow

After enabling notifications, each `BluetoothCharacteristic` exposes a `SharedFlow<ByteArray>`:

```kotlin
// Enable notifications on the remote device
blueFalcon.notifyCharacteristic(peripheral, characteristic, true)

// Collect incoming payloads
characteristic.notifications.collect { value ->
    println("Got ${value.size} bytes")
}
```

Every notification packet is delivered as an explicit `ByteArray` copy, so there is **no race** with `characteristic.value` being overwritten by the next packet.

### Engine-level stream

All notifications across all connected peripherals are also available on a single engine flow:

```kotlin
blueFalcon.engine.characteristicNotifications.collect { notification ->
    println("${notification.peripheral.name} → ${notification.characteristic.uuid}: ${notification.value.size} bytes")
}
```

### Plugin hook

Plugins can override `onNotificationReceived` to react to every notification:

```kotlin
class MyPlugin : BlueFalconPlugin {
    override suspend fun onNotificationReceived(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray
    ) {
        // Log, decode, forward, …
    }
}
```

## Realistic Example: Heart-rate Monitor

```kotlin
// Standard BLE Heart Rate Measurement UUID
val HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"

// Connect and discover…
val hrChar = peripheral.characteristics.first {
    it.uuid.toString().startsWith("00002a37")
}

// Subscribe
blueFalcon.notifyCharacteristic(peripheral, hrChar, true)

// Parse + collect
hrChar.notifications
    .asHeartRate()       // provided extension in the example
    .collect { sample ->
        println("Heart rate: ${sample.bpm} bpm")
    }
```

## Key Points

1. **Enable first, collect second** – call `notifyCharacteristic(…, true)` before (or concurrently with) collecting `characteristic.notifications`.
2. **Explicit payload** – each emission is a defensive `ByteArray` copy; safe to collect slower than the peripheral sends.
3. **Buffer size** – the internal `SharedFlow` uses `extraBufferCapacity = 64`. If the consumer falls behind by more than 64 packets, the oldest are dropped.
4. **Unsubscribe** – call `notifyCharacteristic(…, false)` when done. The flow stays open but stops emitting.

## See Also

- **[Core API](../../library/core/)** – `BluetoothCharacteristic.notifications`, `BlueFalconEngine.characteristicNotifications`
- **[Plugin Development Guide](../../docs/PLUGIN_DEVELOPMENT_GUIDE.md)** – `onNotificationReceived` hook
- **[ADR 0003](../../docs/adr/0003-expose-characteristic-notification-events.md)** – Architecture decision record
- **[ComposeMultiplatform-3.0-Example](../ComposeMultiplatform-3.0-Example/)** – Notification handling in a Compose UI

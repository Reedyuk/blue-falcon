# Plugin Example

This example demonstrates how to create custom plugins for Blue Falcon 3.0.

## What's Included

### ConnectionTimeoutPlugin.kt

A complete, working custom plugin that adds connection timeout functionality to Blue Falcon.

**Features:**
- Implements the `BlueFalconPlugin` interface
- Uses the interceptor pattern (`onBeforeConnect`, `onAfterConnect`)
- Maintains internal state (connection attempt tracking)
- Provides configurable options
- Includes DSL helper function
- Shows multiple usage examples

## Plugin Capabilities

This example plugin demonstrates:

1. **State Management** - Tracks connection attempts
2. **Configuration** - Accepts custom timeout values and callbacks
3. **Interception** - Hooks into connect/disconnect operations
4. **Async Operations** - Uses coroutines for timeout logic
5. **DSL Integration** - Provides fluent configuration API

## Usage

```kotlin
import dev.bluefalcon.example.plugin.*

val blueFalcon = BlueFalcon {
    engine = AndroidEngine(context)
    
    // Install the timeout plugin
    connectionTimeout {
        timeoutMillis = 5000 // 5 seconds
        onTimeout = { peripheral ->
            println("Connection timeout: ${peripheral.name}")
        }
    }
}
```

## Learning from This Example

### 1. Plugin Interface

All plugins must implement `BlueFalconPlugin`:

```kotlin
interface BlueFalconPlugin {
    val name: String
    fun install(blueFalcon: BlueFalcon)
    
    // Optional interceptor methods
    suspend fun onBeforeConnect(...)
    suspend fun onAfterConnect(...)
    // ... and more
}
```

### 2. Interceptor Pattern

Plugins can intercept BLE operations:

```kotlin
override suspend fun onBeforeConnect(
    peripheral: BluetoothPeripheral,
    autoConnect: Boolean,
    next: suspend (BluetoothPeripheral, Boolean) -> Unit
) {
    // Your logic before connection
    println("About to connect...")
    
    // Call next to continue the chain
    next(peripheral, autoConnect)
    
    // Logic after connection (in this method)
}
```

### 3. Configuration Pattern

Make plugins configurable:

```kotlin
data class Config(
    val option1: String = "default",
    val option2: Int = 42,
    val callback: (() -> Unit)? = null
)

class MyPlugin(private val config: Config) : BlueFalconPlugin {
    // Use config.option1, config.option2, etc.
}
```

### 4. DSL Helper

Provide a DSL function for easy installation:

```kotlin
fun BlueFalconConfig.myPlugin(
    configure: MyPlugin.Config.() -> Unit = {}
) {
    val config = MyPlugin.Config().apply(configure)
    install(MyPlugin(config))
}
```

## See Also

- **[Plugin Development Guide](../../docs/PLUGIN_DEVELOPMENT_GUIDE.md)** - Complete guide
- **[Core Plugins](../../library/plugins/)** - Official plugin implementations:
  - LoggingPlugin
  - RetryPlugin
  - CachingPlugin

## Ideas for Custom Plugins

- **RateLimitPlugin** - Limit operation frequency
- **MetricsPlugin** - Track BLE operation statistics
- **SecurityPlugin** - Add encryption/authentication
- **MockPlugin** - Provide fake data for testing
- **ReconnectPlugin** - Auto-reconnect on disconnection
- **FilterPlugin** - Filter devices by RSSI, name patterns
- **HistoryPlugin** - Track connection history
- **NotificationPlugin** - System notifications for events

See the [Plugin Development Guide](../../docs/PLUGIN_DEVELOPMENT_GUIDE.md) for complete instructions on creating your own plugins!

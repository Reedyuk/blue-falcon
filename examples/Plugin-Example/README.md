# Blue Falcon Plugin Example

This example demonstrates Blue Falcon 3.0 plugin system usage.

## Features

- Using built-in plugins (LoggingPlugin, RetryPlugin, CachingPlugin)
- Creating custom plugins
- Plugin composition patterns

## Documentation

See the main examples README and Plugin Development Guide:
- [Examples README](../README.md)
- [Plugin Development Guide](../../docs/PLUGIN_DEVELOPMENT_GUIDE.md)

## Quick Example

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
    }
    
    install(RetryPlugin) {
        maxRetries = 3
    }
}
```

See the full [Plugin Development Guide](../../docs/PLUGIN_DEVELOPMENT_GUIDE.md) for complete examples.

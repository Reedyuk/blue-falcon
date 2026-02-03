package dev.bluefalcon

// Windows doesn't have direct connection priority control like Android
// These values are for API compatibility but are not used on Windows
// as Windows manages connection parameters automatically
actual fun ConnectionPriority.toNative(): Int = when (this) {
    ConnectionPriority.Balanced -> 0
    ConnectionPriority.High -> 1
    ConnectionPriority.Low -> 2
}

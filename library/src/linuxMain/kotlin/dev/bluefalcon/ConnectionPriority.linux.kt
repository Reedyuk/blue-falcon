package dev.bluefalcon

actual fun ConnectionPriority.toNative(): Int = when (this) {
    ConnectionPriority.Balanced -> 0
    ConnectionPriority.High -> 1
    ConnectionPriority.Low -> 2
}

package dev.bluefalcon

enum class ConnectionPriority {
    Balanced,
    High,
    Low
}

expect fun ConnectionPriority.toNative(): Int

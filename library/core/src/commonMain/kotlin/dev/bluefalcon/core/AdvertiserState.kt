package dev.bluefalcon.core

/**
 * State of the local BLE peripheral advertiser / GATT server.
 */
enum class AdvertiserState {
    /** Not currently advertising. */
    Idle,

    /** Advertising and GATT server (if any) are active. */
    Advertising,

    /** Advertising failed; check logs for the underlying platform error. */
    Error
}

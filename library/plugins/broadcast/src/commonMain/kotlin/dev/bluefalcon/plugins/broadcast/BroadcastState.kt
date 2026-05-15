package dev.bluefalcon.plugins.broadcast

/**
 * State of the broadcast plugin / local BLE peripheral.
 */
enum class BroadcastState {
    /** Not broadcasting. */
    Idle,

    /** [DeviceBroadcastPlugin.startBroadcast] has been called; waiting for the advertiser to confirm. */
    Starting,

    /** Advertisement is active and the local GATT server (if any) is running. */
    Broadcasting,

    /** An error occurred starting or maintaining the broadcast; see logs for details. */
    Error
}

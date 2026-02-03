package dev.bluefalcon

/**
 * Represents the bonding state of a Bluetooth peripheral.
 */
enum class BondState {
    /**
     * Indicates the device is not bonded.
     */
    NotBonded,

    /**
     * Indicates bonding is in progress.
     */
    Bonding,

    /**
     * Indicates the device is bonded.
     */
    Bonded
}

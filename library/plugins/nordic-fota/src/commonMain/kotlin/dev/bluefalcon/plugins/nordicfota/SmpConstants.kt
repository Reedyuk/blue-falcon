package dev.bluefalcon.plugins.nordicfota

/**
 * Constants for the Nordic SMP (Simple Management Protocol) used in MCUmgr FOTA updates.
 */
internal object SmpConstants {
    /** SMP service UUID used by Nordic MCUmgr devices */
    const val SMP_SERVICE_UUID = "8d53dc1d-1db7-4cd3-868b-8a527460aa84"

    /** SMP characteristic UUID for read/write operations */
    const val SMP_CHARACTERISTIC_UUID = "da2e7828-fbce-4e01-ae9e-261174997c48"

    // SMP operation types
    const val OP_READ = 0
    const val OP_READ_RESPONSE = 1
    const val OP_WRITE = 2
    const val OP_WRITE_RESPONSE = 3

    // SMP group IDs
    const val GROUP_OS = 0        // OS management
    const val GROUP_IMAGE = 1     // Image management

    // OS management command IDs
    const val OS_ECHO = 0
    const val OS_RESET = 5

    // Image management command IDs
    const val IMAGE_STATE = 0
    const val IMAGE_UPLOAD = 1

    /** SMP header size in bytes */
    const val HEADER_SIZE = 8
}

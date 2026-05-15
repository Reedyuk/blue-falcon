package dev.bluefalcon.engine.rpi

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import dev.bluefalcon.core.L2capException

/**
 * Minimal libc bindings for opening a BlueZ L2CAP connection-oriented channel.
 *
 * The JVM exposes no `AF_BLUETOOTH` socket API, so the kernel L2CAP socket is
 * driven directly via JNA. The channel rides the ACL link that blessed/BlueZ
 * has already established for GATT — no BlueZ D-Bus call is on the data path.
 */
internal interface CLib : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun connect(sockfd: Int, addr: SockaddrL2, addrlen: Int): Int
    fun setsockopt(sockfd: Int, level: Int, optname: Int, optval: Pointer, optlen: Int): Int
    fun read(fd: Int, buf: Pointer, count: NativeLong): NativeLong
    fun write(fd: Int, buf: Pointer, count: NativeLong): NativeLong
    fun close(fd: Int): Int

    companion object {
        val INSTANCE: CLib = Native.load("c", CLib::class.java)
    }
}

/**
 * `struct sockaddr_l2` from `<bluetooth/l2cap.h>`:
 * ```
 * sa_family_t    l2_family;       // offset 0
 * unsigned short l2_psm;          // offset 2  (little-endian)
 * bdaddr_t       l2_bdaddr;       // offset 4  (6 bytes, least-significant byte first)
 * unsigned short l2_cid;          // offset 10
 * uint8_t        l2_bdaddr_type;  // offset 12
 * ```
 */
internal class SockaddrL2 : Structure() {
    @JvmField var l2_family: Short = 0
    @JvmField var l2_psm: Short = 0
    @JvmField var l2_bdaddr: ByteArray = ByteArray(6)
    @JvmField var l2_cid: Short = 0
    @JvmField var l2_bdaddr_type: Byte = 0

    override fun getFieldOrder(): List<String> =
        listOf("l2_family", "l2_psm", "l2_bdaddr", "l2_cid", "l2_bdaddr_type")
}

/**
 * Opens and connects raw L2CAP CoC sockets to BLE peripherals.
 */
internal object LinuxL2cap {
    private const val AF_BLUETOOTH = 31
    private const val SOCK_SEQPACKET = 5
    private const val BTPROTO_L2CAP = 0

    private const val BDADDR_LE_PUBLIC: Byte = 0x01
    private const val BDADDR_LE_RANDOM: Byte = 0x02

    // setsockopt(SOL_BLUETOOTH, BT_SECURITY, ...) for bonded/encrypted channels.
    private const val SOL_BLUETOOTH = 274
    private const val BT_SECURITY = 4
    private const val BT_SECURITY_MEDIUM: Byte = 2

    /**
     * Opens and connects an L2CAP CoC socket to [address] on [psm].
     *
     * @param address BD address string, e.g. "AA:BB:CC:DD:EE:FF"
     * @param addressType BlueZ `Device1.AddressType` — "public" or "random"
     * @param secure when true, request an encrypted (bonded) channel
     * @return the connected socket file descriptor
     * @throws L2capException on any failure; the fd is always closed on error
     */
    fun connect(address: String, addressType: String, psm: Int, secure: Boolean): Int {
        val clib = CLib.INSTANCE
        val fd = clib.socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP)
        if (fd < 0) {
            val errno = Native.getLastError()
            val hint = if (errno == 1) " (EPERM — the process may lack Bluetooth socket permissions)" else ""
            throw L2capException("Failed to create L2CAP socket on PSM $psm (errno $errno)$hint")
        }

        try {
            if (secure) {
                Memory(1).use { opt ->
                    opt.setByte(0, BT_SECURITY_MEDIUM)
                    clib.setsockopt(fd, SOL_BLUETOOTH, BT_SECURITY, opt, 1)
                }
            }

            val addr = SockaddrL2()
            addr.l2_family = AF_BLUETOOTH.toShort()
            addr.l2_psm = psm.toShort()
            writeBdAddr(address, addr.l2_bdaddr)
            addr.l2_cid = 0
            addr.l2_bdaddr_type = when (addressType.lowercase()) {
                "random" -> BDADDR_LE_RANDOM
                else -> BDADDR_LE_PUBLIC
            }

            if (clib.connect(fd, addr, addr.size()) < 0) {
                val errno = Native.getLastError()
                throw L2capException("Failed to connect L2CAP channel on PSM $psm (errno $errno)")
            }
            return fd
        } catch (e: Throwable) {
            clib.close(fd)
            throw e
        }
    }

    /** Writes "AA:BB:CC:DD:EE:FF" into [out] as the 6-byte little-endian `bdaddr_t`. */
    private fun writeBdAddr(address: String, out: ByteArray) {
        val parts = address.split(":")
        require(parts.size == 6) { "Invalid Bluetooth address: $address" }
        for (i in 0 until 6) {
            // bdaddr_t stores the address least-significant byte first.
            out[i] = parts[5 - i].toInt(16).toByte()
        }
    }
}

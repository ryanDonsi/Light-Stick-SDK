package io.lightstick.sdk.ble.ota

import android.util.Log
import io.lightstick.sdk.ble.util.CrcUtil

/**
 * Telink OTA packet parser.
 *
 * Follows official Telink protocol format:
 * Each data packet = [2B index] + [N bytes data] + [2B CRC]
 */
class TelinkOtaPacketParser(
    private var pduLength: Int = 16  // OTA default packet size
) {
    private var total: Int = 0
    private var index: Int = -1
    private var data: ByteArray = byteArrayOf()

    /**
     * Sets the full OTA firmware binary and initializes the parser.
     */
    fun set(data: ByteArray, mtuPayloadSize: Int = 16) {
        clear()
        this.pduLength = mtuPayloadSize.coerceAtMost(16)
        this.data = data
        this.total = (data.size + pduLength - 1) / pduLength
    }


    fun clear() {
        index = -1
        total = 0
        data = byteArrayOf()
    }

    fun hasNext(): Boolean = total > 0 && index + 1 < total
    fun isLast(): Boolean = index + 1 == total
    fun getIndex(): Int = index
    fun getNextPacketIndex(): Int = index + 1

    /**
     * Returns the next OTA packet and updates internal index.
     */
    fun getNextPacket(): ByteArray {
        val idx = getNextPacketIndex()
        val packet = getPacket(idx)
        index = idx
        return packet
    }

    fun getProgress(): Int {
        return if (total == 0) 0 else ((index + 1) * 100) / total
    }

    /**
     * Generates a full OTA packet:
     * [index_L, index_H] + [data] + [crc_L, crc_H]
     */
    fun getPacket(i: Int): ByteArray {
        val offset = i * pduLength
        val size = (data.size - offset).coerceAtMost(pduLength)

        // 전체 패킷: [index(2)] + [data(<=16)] + [crc(2)] = 2 + pduLength + 2 = 20B
        val packet = ByteArray(4 + pduLength) { 0xFF.toByte() }

        // Set index
        fillIndex(packet, i)

        // Copy actual data into packet[2 .. 2+size]
        data.copyInto(packet, 2, offset, offset + size)

        // Compute CRC 20B
        val crc = CrcUtil.crc16Telink(packet)
        Log.d("Telink-OTA", "📦 CRC Src Packet: ${packet.joinToString(" ") { "%02X".format(it) }}")
        // Fill CRC in-place at end of packet
        fillCrc(packet, crc)

        return packet
    }

    /**
     * Inserts 2-byte index into packet[0..1], little-endian.
     */
    private fun fillIndex(packet: ByteArray, index: Int) {
        packet[0] = (index and 0xFF).toByte()
        packet[1] = ((index shr 8) and 0xFF).toByte()
    }

    /**
     * Inserts CRC (little-endian) to the end of given packet.
     */
    fun fillCrc(packet: ByteArray, crc: Int) {
        val offset = packet.size - 2
        packet[offset] = (crc and 0xFF).toByte()
        packet[offset + 1] = ((crc shr 8) and 0xFF).toByte()
    }

}

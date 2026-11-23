package com.lightstick.device

/**
 * Wand / relay firmware version, per the BLE protocol v2.2 §6 versioning rule:
 * `<protocol major>.<protocol minor><3-digit build>` (e.g. `2.200`, `2.201`, `2.300`).
 *
 * The firmware version tracks the protocol version, and the build number grows within one
 * protocol (`2.200 -> 2.201 -> 2.202 ...`). Comparison is numeric — **major first, then
 * (minor + build)** — never lexical, so `2.200 < 2.201 < 2.300`.
 *
 * All relays and wands are expected to run firmware >= [PROTOCOL_2_2]; the pre-2.2 scheme
 * (`1.0.x`) is not supported and [parse] returns `null` for it.
 *
 * @since 2.200.0
 */
data class FirmwareVersion(val major: Int, val minorBuild: Int) : Comparable<FirmwareVersion> {

    override fun compareTo(other: FirmwareVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minorBuild })

    override fun toString(): String = "$major.${minorBuild.toString().padStart(3, '0')}"

    companion object {
        /** First firmware that implements protocol v2.2 (Control / FF0A). */
        @JvmField val PROTOCOL_2_2 = FirmwareVersion(2, 200)

        /**
         * Parses a DIS Firmware Revision string of the form `<major>.<fraction>` (e.g. `"2.200"`,
         * `"2.201"`). The fraction is 3 digits per spec; shorter ones (`"2.2"`) are right-padded
         * (`2.200`). A leading `v` and trailing NULs/whitespace are ignored.
         *
         * @return the parsed version, or `null` if [raw] is not of that form (including any
         *         multi-dot legacy string such as `"1.0.7"`).
         */
        @JvmStatic
        fun parse(raw: String?): FirmwareVersion? {
            val s = raw?.trim { it.isWhitespace() || it == '\u0000' }
                ?.removePrefix("v")?.removePrefix("V") ?: return null
            val parts = s.split('.')
            if (parts.size != 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val frac = parts[1].takeWhile { it.isDigit() }
            if (frac.isEmpty() || frac.length > 3) return null
            return FirmwareVersion(major, frac.padEnd(3, '0').toInt())
        }
    }
}

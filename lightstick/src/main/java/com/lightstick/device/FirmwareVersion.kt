package com.lightstick.device

/**
 * Wand / relay firmware version, per the BLE protocol v2.2 §6 versioning rule:
 * `<protocol major>.<protocol minor><3-digit build>` (e.g. `2.200`, `2.201`, `2.300`).
 *
 * Comparison is numeric — **major first, then (minor + build)** — never lexical, so
 * `2.200 < 2.201 < 2.300`, and the legacy scheme (`1.0.7`) sorts below `2.200`.
 *
 * @since 2.200.0
 */
data class FirmwareVersion(val major: Int, val minorBuild: Int) : Comparable<FirmwareVersion> {

    /** Protocol minor digit (hundreds place of [minorBuild]); `2.200` → 2. */
    val protocolMinor: Int get() = minorBuild / 100

    override fun compareTo(other: FirmwareVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minorBuild })

    /**
     * True if this firmware supports protocol `major.minor`, i.e. is `>= major.minor00`
     * (e.g. `supportsProtocol(2, 2)` ⇔ `>= 2.200`).
     */
    fun supportsProtocol(protocolMajor: Int, protocolMinor: Int): Boolean =
        this >= FirmwareVersion(protocolMajor, protocolMinor * 100)

    override fun toString(): String = "$major.${minorBuild.toString().padStart(3, '0')}"

    companion object {
        /** Minimum firmware that implements protocol v2.2 (Control / FF0A). */
        @JvmField val PROTOCOL_2_2 = FirmwareVersion(2, 200)

        /**
         * Parses a DIS Firmware Revision string.
         *
         * Accepts `"2.200"` (new scheme: fraction is minor+build, 3 digits). Legacy strings with
         * more than one dot (`"1.0.7"`) or a leading `v` are parsed as `major` with
         * `minorBuild = 0`, which sorts below every `2.xxx` version. Returns `null` for strings
         * that carry no leading integer.
         */
        @JvmStatic
        fun parse(raw: String?): FirmwareVersion? {
            val s = raw?.trim()?.trimEnd('\u0000')?.removePrefix("v")?.removePrefix("V") ?: return null
            val parts = s.split('.')
            val major = parts.firstOrNull()?.toIntOrNull() ?: return null
            if (parts.size != 2) return FirmwareVersion(major, 0) // legacy x.y.z
            val frac = parts[1].takeWhile { it.isDigit() }
            if (frac.isEmpty()) return FirmwareVersion(major, 0)
            // The fraction is always 3 digits per spec; shorter ones ("2.2") are right-padded.
            return FirmwareVersion(major, frac.padEnd(3, '0').toInt())
        }
    }
}

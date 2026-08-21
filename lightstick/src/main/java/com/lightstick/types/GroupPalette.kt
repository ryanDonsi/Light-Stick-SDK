package com.lightstick.types

/**
 * Fixed RGB palette for groups 1–20 (Glowsync group mapping spec v2.0).
 *
 * Generated with the "golden angle" method (hue advances ~137.508° per group, S/V
 * at 100%) so that consecutive group numbers are always close to maximally distinct
 * in hue. The actual RGB values are carried in every message, so relays and
 * lightsticks never need to know this table — it only exists so that apps built on
 * this SDK render the same group colors as the reference implementation.
 *
 * These are the corrected v2.2 values (2026-08-13): the initial rollout had groups
 * 3 and 4 drifted off the formula (group 3 ≈ group 16, group 1 ≈ group 4), which is
 * fixed in this table.
 *
 * @since 2.0.0
 */
object GroupPalette {

    /** Minimum valid group id. */
    const val MIN_GROUP_ID: Int = 1

    /** Maximum valid group id. */
    const val MAX_GROUP_ID: Int = 20

    /** Palette indexed 0-based internally; use [colorFor] for 1-based group ids. */
    @JvmField
    val PALETTE: List<Color> = listOf(
        Color(255, 0, 0),    // 1  RED
        Color(0, 255, 74),   // 2  SPRING GREEN
        Color(149, 0, 255),  // 3  VIOLET
        Color(255, 223, 0),  // 4  YELLOW
        Color(0, 212, 255),  // 5  SKY BLUE
        Color(255, 0, 138),  // 6  PINK-MAGENTA
        Color(64, 255, 0),   // 7  YELLOW-GREEN
        Color(11, 0, 255),   // 8  BLUE-VIOLET
        Color(255, 85, 0),   // 9  ORANGE
        Color(0, 255, 160),  // 10 TEAL-GREEN
        Color(234, 0, 255),  // 11 MAGENTA-PURPLE
        Color(202, 255, 0),  // 12 CHARTREUSE
        Color(0, 127, 255),  // 13 AZURE
        Color(255, 0, 53),   // 14 RED-PINK
        Color(0, 255, 22),   // 15 GREEN
        Color(96, 0, 255),   // 16 PURPLE-BLUE
        Color(255, 171, 0),  // 17 AMBER
        Color(0, 255, 245),  // 18 CYAN
        Color(255, 0, 191),  // 19 HOT PINK
        Color(116, 255, 0)   // 20 LIME
    )

    /**
     * Returns the palette color for [groupId] (1..20).
     *
     * @throws IllegalArgumentException If [groupId] is outside [MIN_GROUP_ID]..[MAX_GROUP_ID].
     */
    @JvmStatic
    fun colorFor(groupId: Int): Color {
        require(groupId in MIN_GROUP_ID..MAX_GROUP_ID) {
            "groupId must be within $MIN_GROUP_ID..$MAX_GROUP_ID (got $groupId)"
        }
        return PALETTE[groupId - 1]
    }
}

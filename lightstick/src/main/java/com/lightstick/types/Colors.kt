package com.lightstick.types

/**
 * A collection of commonly used predefined [Color] constants.
 *
 * These color presets are provided for convenience and readability when building
 * LED effects or testing color transitions. Developers can still construct custom
 * [Color] values with any RGB combination in the inclusive range [0, 255].
 *
 * Each constant is exposed as a `@JvmField` so that it can be accessed directly
 * from both Kotlin and Java without getters.
 *
 * @see Color for constructing custom RGB values.
 * @since 1.0.0
 *
 * @sample com.lightstick.samples.ColorSamples.samplePresetColors
 */
object Colors {

    /** Black (#000000). */ @JvmField val BLACK = Color(0, 0, 0)
    /** White (#FFFFFF). */ @JvmField val WHITE = Color(255, 255, 255)
    /** Red (#FF0000). */ @JvmField val RED = Color(255, 0, 0)
    /** Green (#00FF00). */ @JvmField val GREEN = Color(0, 255, 0)
    /** Blue (#0000FF). */ @JvmField val BLUE = Color(0, 0, 255)
    /** Yellow (#FFFF00). */ @JvmField val YELLOW = Color(255, 255, 0)
    /** Cyan (#00FFFF). */ @JvmField val CYAN = Color(0, 255, 255)
    /** Magenta (#FF00FF). */ @JvmField val MAGENTA = Color(255, 0, 255)
    /** Orange (#FFA500). */ @JvmField val ORANGE = Color(255, 165, 0)
    /** Purple (#800080). */ @JvmField val PURPLE = Color(128, 0, 128)
    /** Pink (#FF69B4). */ @JvmField val PINK = Color(255, 105, 180)
}

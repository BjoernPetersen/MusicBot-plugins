package net.bjoernpetersen.musicbot.mpv.control

// Reference: https://mpv.io/manual/master/#properties
enum class MpvProperty(val externalName: String) {
    /**
     * The playback position in seconds (type: double).
     */
    PLAYBACK_POSITION("time-pos"),

    /**
     * Whether the player is paused (type: "yes" or "no" string)
     */
    PAUSE("pause"),
}

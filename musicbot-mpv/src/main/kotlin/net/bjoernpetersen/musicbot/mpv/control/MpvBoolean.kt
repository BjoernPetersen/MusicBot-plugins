package net.bjoernpetersen.musicbot.mpv.control;

object MpvBoolean {
    const val TRUE = "yes"
    const val FALSE = "no"
}

fun String.parseMpvBoolean(): Boolean {
    return when (this) {
        MpvBoolean.TRUE -> true
        MpvBoolean.FALSE -> false
        else -> throw IllegalArgumentException("Unknown MPV bool: $this")
    }
}

fun Boolean.toMpvBoolean(): String {
    return if (this) MpvBoolean.TRUE else MpvBoolean.FALSE
}

package net.bjoernpetersen.musicbot.mpv.control

// Reference: https://mpv.io/manual/master/#list-of-events
enum class MpvEvent(val externalName: String) {
    FILE_LOADED("file-loaded"),
    SHUTDOWN("shutdown"),
}

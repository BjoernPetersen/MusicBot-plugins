package net.bjoernpetersen.spotify.suggester

import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.DeserializationException

internal data class PlaylistChoice(val id: String, val displayName: String) {
    companion object : ConfigSerializer<PlaylistChoice> {
        @Throws(DeserializationException::class)
        override fun deserialize(string: String): PlaylistChoice {
            string.split(';').let {
                if (it.size < 2) throw DeserializationException()
                else {
                    val playlistId = it[0]
                    val displayName = it.subList(1, it.size).joinToString(";")
                    return PlaylistChoice(playlistId, displayName)
                }
            }
        }

        override fun serialize(obj: PlaylistChoice): String {
            return "${obj.id};${obj.displayName}"
        }
    }
}

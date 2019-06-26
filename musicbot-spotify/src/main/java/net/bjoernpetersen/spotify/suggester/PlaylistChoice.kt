package net.bjoernpetersen.spotify.suggester

import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.SerializationException

internal data class PlaylistChoice(val id: String, val displayName: String) {

    object Serializer : ConfigSerializer<PlaylistChoice> {
        @Throws(SerializationException::class)
        override fun deserialize(string: String): PlaylistChoice {
            string.split(';').let {
                if (it.size < 2) throw SerializationException()
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

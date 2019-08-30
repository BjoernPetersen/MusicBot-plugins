package net.bjoernpetersen.musicbot.radio

import net.bjoernpetersen.m3u.M3uParser
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.ExperimentalConfigDsl
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.SerializationException
import net.bjoernpetersen.musicbot.api.config.choiceBox
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import java.nio.file.Files
import java.util.Base64
import javax.inject.Inject

@IdBase("Web radio")
class RadioSuggester : Suggester {
    override val name: String = "Web radio"
    override val description: String = "Plays a specific web radio station"

    @Inject
    private lateinit var provider: RadioProvider
    private lateinit var radioStation: Config.SerializedEntry<Song>

    override val subject: String
        get() {
            if (this::radioStation.isInitialized) {
                val song = radioStation.get()
                if (song != null) {
                    return song.title
                }
            }
            return name
        }

    private lateinit var song: Song

    @UseExperimental(ExperimentalConfigDsl::class)
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        radioStation = config.serialized("radioStation") {
            description = "The entry from the playlist file to play"
            serializer = SongSerializer(provider)
            check(NonnullConfigChecker)
            choiceBox {
                describe { it.title }
                lazy()
                refresh {
                    val playlistFile = provider.playlistFile.get()
                    if (playlistFile != null && Files.isRegularFile(playlistFile)) {
                        M3uParser.parse(playlistFile)
                            .mapIndexed { index, entry ->
                                provider.createSong(index.toString(), entry.title!!)
                            }
                            .sortedBy { it.title }
                    } else emptyList()
                }
            }
        }

        return listOf(radioStation)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        song = radioStation.get() ?: throw InitializationException("radioStation not set")
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        return listOf(song)
    }

    override suspend fun suggestNext(): Song {
        return song
    }

    override suspend fun removeSuggestion(song: Song) = Unit

    override suspend fun close() = Unit
}

private class SongSerializer(private val provider: RadioProvider) : ConfigSerializer<Song> {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    private fun String.encode(): String {
        return String(encoder.encode(toByteArray()), Charsets.UTF_8)
    }

    private fun String.decode(): String {
        return try {
            String(decoder.decode(this), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw SerializationException()
        }
    }

    override fun serialize(obj: Song): String {
        val encodedId = obj.id.encode()
        val encodedTitle = obj.title.encode()
        return "$encodedId;$encodedTitle"
    }

    @Suppress("MagicNumber")
    override fun deserialize(string: String): Song {
        val parts = string.split(';')
        if (parts.size != 2) throw SerializationException()
        val id = parts[0].decode()
        val title = parts[1].decode()
        return provider.createSong(id, title)
    }
}

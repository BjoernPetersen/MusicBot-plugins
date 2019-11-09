package net.bjoernpetersen.musicbot.radio

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import net.bjoernpetersen.m3u.M3uParser
import net.bjoernpetersen.m3u.model.M3uEntry
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.PathSerializer
import net.bjoernpetersen.musicbot.api.config.openFile
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.loader.SongLoadingException
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.Provider
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3StreamPlaybackFactory

@IdBase("Web radio")
class RadioProvider : Provider, CoroutineScope by PluginScope(Dispatchers.IO) {
    override val name: String = "Web radio"
    override val description: String = "Plays a web radio station"

    lateinit var playlistFile: Config.SerializedEntry<Path>
        private set

    override val subject: String
        get() = name

    private lateinit var entries: List<M3uEntry>
    private lateinit var songs: List<Song>
    @Inject
    private lateinit var playbackFactory: Mp3StreamPlaybackFactory

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        playlistFile = config.serialized("playlistFile") {
            description = "An m3u playlist file containing radio station URLs"
            serializer = PathSerializer
            openFile()
            check {
                when {
                    it == null -> "Must be set"
                    !Files.isRegularFile(it) -> "Not a file"
                    else -> null
                }
            }
        }
        return listOf(playlistFile)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun loadSong(song: Song): Resource {
        val entry = song.id.toIntOrNull()?.let { entries.getOrNull(it) }
            ?: throw SongLoadingException()
        return UrlResource(entry.location.url)
    }

    override suspend fun supplyPlayback(song: Song, resource: Resource): Playback {
        val urlResource = resource as UrlResource
        return playbackFactory.createPlayback(urlResource.url)
    }

    fun createSong(index: String, title: String): Song {
        return song(index) {
            this.title = title
            description = name
        }
    }

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        withContext(coroutineContext) {
            initStateWriter.state("Parsing playlist file")
            entries = M3uParser.parse(playlistFile.get()!!).filter { it.title != null }
            songs = entries.mapIndexed { index, entry ->
                createSong(index.toString(), entry.title!!)
            }
        }
    }

    override suspend fun lookup(id: String): Song {
        return id.toIntOrNull()?.let { songs.getOrNull(it) } ?: throw NoSuchSongException(id)
    }

    override suspend fun search(query: String, offset: Int): List<Song> {
        return songs.asSequence()
            .sortedByDescending { FuzzySearch.ratio(query, it.title) }
            .drop(offset)
            .take(MAX_SEARCH_RESULTS)
            .toList()
    }

    override suspend fun close() {
        run { cancel() }
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 50
    }
}

private data class UrlResource(val url: URL) : Resource {
    override val isValid: Boolean
        get() = true

    override suspend fun free() = Unit
}

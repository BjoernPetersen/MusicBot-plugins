package net.bjoernpetersen.localmp3.suggester

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import net.bjoernpetersen.localmp3.provider.Mp3Provider
import net.bjoernpetersen.localmp3.provider.extension
import net.bjoernpetersen.localmp3.provider.toId
import net.bjoernpetersen.m3u.M3uParser
import net.bjoernpetersen.m3u.model.MediaPath
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.PathSerializer
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.config.openFile
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.BrokenSuggesterException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedList
import javax.inject.Inject

@Suppress("TooManyFunctions")
@IdBase("M3U Playlist")
class PlaylistMp3Suggester : Suggester, CoroutineScope by PluginScope() {
    @Inject
    private lateinit var provider: Mp3Provider

    private lateinit var customSubject: Config.StringEntry
    private lateinit var playlistPath: Config.SerializedEntry<Path>
    private lateinit var shuffle: Config.BooleanEntry

    override val name = "M3U Playlist"
    override val description = "Plays local MP3s from a M3U file."

    override val subject: String
        get() = customSubject.get()!!

    private lateinit var allSongs: List<Song>

    private var nextSongs: LinkedList<Song> = LinkedList()

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        customSubject = config.string("DisplayName") {
            description = "Name to display in clients"
            check { null }
            uiNode = TextBox
            default("MP3 playlist")
        }

        playlistPath = config.serialized("playlistFile") {
            description = "M3U playlist file"
            check {
                when {
                    it == null -> "Please select a playlist file."
                    it.extension.toLowerCase() != "m3u" -> "Must be an .m3u file!"
                    else -> null
                }
            }
            serializer = PathSerializer
            openFile()
        }

        shuffle = config.boolean("shuffle") {
            description = "Whether the playlist should be shuffled"
            default = true
        }

        return listOf(customSubject, playlistPath, shuffle)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        progressFeedback.state("Loading entries from playlist file...")
        val playlistPath = this.playlistPath.get()
            ?: throw InitializationException("No playlist file selected.")

        val entries = loadRecursively(progressFeedback, playlistPath)

        progressFeedback.state("Mapping M3U entries to songs...")
        allSongs = loadSongs(progressFeedback, entries)
        progressFeedback.state("Found ${allSongs.size} songs from ${entries.size} M3U entries")
    }

    private fun loadRecursively(
        feedback: ProgressFeedback,
        playlistPath: Path,
        result: MutableList<Path> = LinkedList()
    ): List<Path> {
        feedback.state("Loading entries from file $playlistPath")

        val entries = try {
            M3uParser.parse(playlistPath)
        } catch (e: IOException) {
            throw InitializationException(e)
        }

        entries.forEach {
            val location = it.location
            when {
                location !is MediaPath -> {
                    feedback.warning("Ignoring non-file entry: ${location.url}")
                }
                location.path.extension.toLowerCase() == "m3u" -> {
                    loadRecursively(feedback, location.path, result)
                }
                else -> result.add(location.path)
            }
        }

        return result
    }

    private suspend fun loadSongs(
        feedback: ProgressFeedback,
        entries: List<Path>
    ): List<Song> {
        val songs: MutableList<Song> = ArrayList(entries.size)

        entries.forEach { entry ->
            val id = entry.toId()
            try {
                val song = provider.lookup(id)
                songs.add(song)
            } catch (e: NoSuchSongException) {
                feedback.warning("Song not found: $entry")
            }
        }

        if (shuffle.get()) {
            songs.shuffle()
        }
        return Collections.unmodifiableList(songs)
    }

    override suspend fun close() {
        run { cancel() }
    }

    private fun refreshNextSongs() {
        nextSongs = LinkedList(allSongs)
        if (nextSongs.isEmpty()) {
            throw BrokenSuggesterException()
        }
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        if (nextSongs.isEmpty()) refreshNextSongs()
        return nextSongs.subList(
            0,
            minOf(nextSongs.size, minOf(MAX_SUGGESTIONS, maxOf(1, maxLength)))
        )
    }

    override suspend fun suggestNext(): Song {
        getNextSuggestions(1)
        return nextSongs.pop()
    }

    override suspend fun removeSuggestion(song: Song) {
        nextSongs.remove(song)
    }

    private companion object {
        const val MAX_SUGGESTIONS = 20
    }
}

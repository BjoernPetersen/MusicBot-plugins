package net.bjoernpetersen.video.suggester

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.PathSerializer
import net.bjoernpetersen.musicbot.api.config.actionButton
import net.bjoernpetersen.musicbot.api.config.choiceBox
import net.bjoernpetersen.musicbot.api.config.openDirectory
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.SongEntry
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.BrokenSuggesterException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.video.provider.VideoProvider
import net.bjoernpetersen.video.provider.toId
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.math.min
import kotlin.streams.asSequence

private class FolderSuggesterConfig(config: Config, provider: VideoProvider) {
    val directory by config.serialized<Path> {
        description = "Directory to suggest videos from"
        serializer = PathSerializer
        provider.folder.get()?.let(::default)
        openDirectory()
        check {
            if (it == null) "Required"
            else if (!it.startsWith(provider.folder.get()!!)) "Needs to be in provider root folder"
            else null
        }
    }

    val current by config.string {
        description = "Current video"
        actionButton {
            label = "Clear"
            describe { it }
            action {
                it.set(null)
                true
            }
        }
        check { null }
    }

    val sortMode by config.serialized<SortMode> {
        description = "Sorting strategy for files"
        serializer = SortMode
        default(SortMode.NONE)
        choiceBox {
            describe { it.friendlyName }
            refresh { SortMode.values().toList() }
        }
        check { null }
    }

    fun getShownEntries(): List<Config.Entry<*>> = listOf(
        directory,
        current,
        sortMode
    )
}

@IdBase("Video folder")
class FolderSuggester : Suggester {
    private val logger = KotlinLogging.logger { }

    override val description: String
        get() = "Plays videos from a selected directory"
    override val name: String
        get() = "Video folder"

    @Inject
    private lateinit var provider: VideoProvider
    private lateinit var config: FolderSuggesterConfig

    override val subject: String
        get() {
            val result = if (::config.isInitialized)
                config.directory.get()?.fileName?.toString()
            else null
            return result ?: name
        }

    private lateinit var songById: Map<String, Song>
    private lateinit var nextSuggestions: MutableList<Song>

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        this.config = FolderSuggesterConfig(config, provider)
        return this.config.getShownEntries()
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Loading songs...")
        val songs = loadSongs()
        songById = songs.associateBy { it.id }
        nextSuggestions = songs
        if (nextSuggestions.isEmpty())
            throw InitializationException("No songs found")

        initStateWriter.state("Restoring state")
        val currentId = config.current.get()
        if (currentId != null) {
            val index = nextSuggestions.indexOfFirst { it.id == currentId }
            if (index == -1) {
                logger.warn { "Current ID not found in songs, ignoring it..." }
            } else {
                nextSuggestions = nextSuggestions.subList(index, nextSuggestions.size)
            }
        }
    }

    private fun loadSongs(): MutableList<Song> {
        return Files.list(config.directory.get()!!)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .mapNotNull {
                runBlocking {
                    try {
                        provider.lookup(it.toId())
                    } catch (e: NoSuchSongException) {
                        null
                    }
                }
            }
            .toMutableList()
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        return nextSuggestions.subList(0, min(nextSuggestions.size, maxLength))
    }

    override suspend fun removeSuggestion(song: Song) {
        nextSuggestions.remove(song)
    }

    override suspend fun notifyPlayed(songEntry: SongEntry) {
        super.notifyPlayed(songEntry)
        if (songEntry.user == null) config.current.set(songEntry.song.id)
    }

    override suspend fun suggestNext(): Song {
        val suggestion = nextSuggestions.firstOrNull()
        if (suggestion == null) {
            config.current.set(null)
            throw BrokenSuggesterException("Suggestions are depleted")
        }
        return suggestion
    }

    override suspend fun close() = Unit
}

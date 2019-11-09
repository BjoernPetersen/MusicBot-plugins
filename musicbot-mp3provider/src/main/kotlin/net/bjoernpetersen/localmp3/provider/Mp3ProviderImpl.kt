package net.bjoernpetersen.localmp3.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ExperimentalConfigDsl
import net.bjoernpetersen.musicbot.api.config.PathSerializer
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.config.openDirectory
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.loader.SongLoadingException
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.image.AlbumArtSupplier
import net.bjoernpetersen.musicbot.spi.image.ImageData
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import net.bjoernpetersen.musicbot.spi.util.FileStorage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@Suppress("TooManyFunctions")
class Mp3ProviderImpl : Mp3Provider, AlbumArtSupplier, CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val logger = KotlinLogging.logger { }

    private lateinit var config: Mp3ProviderConfig

    @Inject
    private lateinit var playbackFactory: Mp3PlaybackFactory
    @Inject
    private lateinit var fileStorage: FileStorage
    private lateinit var songById: Map<String, Song>

    override val name = "Local MP3"
    override val description = "MP3s from some local directory"
    override val subject: String
        get() {
            val fromConfig = if (this::config.isInitialized)
                config.customSubject.get() ?: config.folder.get()?.fileName?.toString()
            else null
            return fromConfig ?: name
        }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        this.config = Mp3ProviderConfig(config)
        return this.config.allEntries
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Initializing...")
        val folder = config.folder.get() ?: throw InitializationException()
        withContext(coroutineContext) {
            initStateWriter.state("Looking for songs...")
            val start = Instant.now()
            songById = initializeSongs(initStateWriter, folder, config.recursive.get())
            val duration = Duration.between(start, Instant.now())
            initStateWriter.state("Done (found ${songById.size} in ${duration.seconds} seconds).")
        }
    }

    override suspend fun loadSong(song: Song): Resource {
        val path = song.id.toPath()
        if (!Files.isRegularFile(path)) throw SongLoadingException("File not found: $path")
        return NoResource
    }

    override suspend fun supplyPlayback(song: Song, resource: Resource): Playback {
        return withContext(coroutineContext) {
            playbackFactory.createPlayback(song.id.toPath().toFile())
        }
    }

    private suspend fun initializeSongs(
        initWriter: InitStateWriter,
        root: Path,
        recursive: Boolean
    ): Map<String, Song> {
        val indexDir = fileStorage.forPlugin(this).toPath()
        return Index(this, indexDir, root).use {
            it.load(initWriter, recursive)
        }
    }

    override fun getAlbumArt(songId: String): ImageData? {
        val path = songId.toPath()
        val basePath = config.folder.get()!!
        if (!path.normalize().startsWith(basePath.normalize())) {
            logger.warn { "Tried to load data from restricted file: $path" }
            return null
        }
        return loadImage(path) ?: loadFolderImage(path)
    }

    override suspend fun close() {
        job.cancel()
    }

    override fun getSongs(): Collection<Song> {
        return songById.values
    }

    override suspend fun search(query: String, offset: Int): List<Song> {
        return songById.values.asSequence()
            .sortedByDescending {
                val titleScore = FuzzySearch.ratio(query, it.title) * TITLE_WEIGHT
                val descriptionScore = FuzzySearch.ratio(query, it.description) * DESCRIPTION_WEIGHT
                maxOf(titleScore, descriptionScore)
            }
            .drop(offset)
            .take(MAX_SEARCH_RESULTS)
            .toList()
    }

    override suspend fun lookup(id: String): Song = songById[id]
        ?: throw NoSuchSongException(id, Mp3Provider::class)

    private companion object {
        const val TITLE_WEIGHT = 1.0
        const val DESCRIPTION_WEIGHT = 0.8
        const val MAX_SEARCH_RESULTS = 50
    }
}

@UseExperimental(ExperimentalConfigDsl::class)
private class Mp3ProviderConfig(config: Config) {
    val folder by config.serialized<Path> {
        description = "The folder the MP3s should be taken from"
        check { path ->
            if (path == null) "Required"
            else if (!Files.isDirectory(path)) "Not a directory"
            else null
        }
        serializer = PathSerializer
        openDirectory()
    }
    val recursive by config.boolean {
        description = "Whether to search the folder recursively"
        default = false
    }
    val customSubject = config.string("DisplayName") {
        description = "Name to display in clients, defaults to folder name"
        check { null }
        uiNode = TextBox
    }

    val allEntries: List<Config.Entry<*>>
        get() = listOf(folder, recursive, customSubject)
}

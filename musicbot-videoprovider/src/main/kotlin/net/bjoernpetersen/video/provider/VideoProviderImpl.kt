package net.bjoernpetersen.video.provider

import com.google.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.streams.asSequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.PathSerializer
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.config.openDirectory
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.loader.SongLoadingException
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.song
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.AviPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.ExperimentalVideoFilePlayback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.FilePlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.MkvPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp4PlaybackFactory

@Suppress("TooManyFunctions")
@UseExperimental(ExperimentalVideoFilePlayback::class)
class VideoProviderImpl : VideoProvider, CoroutineScope by PluginScope(Dispatchers.IO) {
    private val logger = KotlinLogging.logger { }

    override lateinit var folder: Config.SerializedEntry<Path>
        private set
    private lateinit var recursive: Config.BooleanEntry

    @Inject(optional = true)
    private lateinit var aviPlayback: AviPlaybackFactory
    @Inject(optional = true)
    private lateinit var mkvPlayback: MkvPlaybackFactory
    @Inject(optional = true)
    private lateinit var mp4Playback: Mp4PlaybackFactory
    @Inject
    private lateinit var playback: Playbacks

    private lateinit var songById: Map<String, Song>

    private var customSubject: Config.StringEntry? = null

    override val name = "Local Videos"
    override val description = "Videos from some local directory"
    override val subject: String
        get() {
            val custom = customSubject?.get()
            if (custom != null) return custom
            if (::folder.isInitialized) {
                val folderName = folder.get()?.fileName?.toString()
                if (folderName != null) return folderName
            }
            return name
        }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        folder = config.serialized("folder") {
            description = "The folder the video files should be taken from"
            serializer = PathSerializer
            check { path ->
                if (path == null) "Required"
                else if (!Files.isDirectory(path)) "Not a directory"
                else null
            }
            openDirectory()
        }
        recursive = config.boolean("recursive") {
            description = "Whether to search the folder recursively"
            default = false
        }

        customSubject = config.string("DisplayName") {
            description = "Name to display in clients, defaults to folder name"
            check { null }
            uiNode = TextBox
        }

        return listOf(folder, recursive, customSubject!!)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Initializing...")
        val folder = folder.get() ?: throw InitializationException()
        withContext(coroutineContext) {
            initStateWriter.state("Looking for songs...")
            val start = Instant.now()
            songById = initializeSongs(initStateWriter, folder, recursive.get())
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
            val path = song.id.toPath()
            playback[path].createPlayback(path.toFile())
        }
    }

    private suspend fun initializeSongs(
        initWriter: InitStateWriter,
        root: Path,
        recursive: Boolean
    ): Map<String, Song> =
        (if (recursive) Files.walk(root).asSequence() else Files.list(root).asSequence())
            .filter { Files.isRegularFile(it) }
            .filter { it.extension.toLowerCase(Locale.US) in playback.supported }
            .map { createSongAsync(initWriter, it) }
            .toList().awaitAll()
            .filterNotNull()
            .associateBy(Song::id) { it }

    private fun createSongAsync(initWriter: InitStateWriter, path: Path): Deferred<Song?> = async {
        logger.debug { "Loading tag for '$path'" }
        createSong(path).also {
            if (it == null) {
                initWriter.warning("Could not load song from '$path'")
            } else {
                initWriter.state("""Loaded song ${it.title}""")
            }
        }
    }

    private fun createSong(path: Path): Song? {
        return song(path.toId()) {
            title = path.fileNameWithoutExtension
            description = path.parent.fileName.toString()
        }
    }

    override suspend fun close() {
        run { cancel() }
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
        ?: throw NoSuchSongException(id, VideoProvider::class)

    private companion object {
        const val TITLE_WEIGHT = 1.0
        const val DESCRIPTION_WEIGHT = 0.8
        const val MAX_SEARCH_RESULTS = 50
    }
}

@UseExperimental(ExperimentalVideoFilePlayback::class)
private class Playbacks @Inject private constructor() {
    @Inject(optional = true)
    private lateinit var aviPlayback: AviPlaybackFactory
    @Inject(optional = true)
    private lateinit var mkvPlayback: MkvPlaybackFactory
    @Inject(optional = true)
    private lateinit var mp4Playback: Mp4PlaybackFactory

    val supported: Set<String> by lazy {
        HashSet<String>().apply {
            if (::aviPlayback.isInitialized) add("avi")
            if (::mkvPlayback.isInitialized) add("mkv")
            if (::mp4Playback.isInitialized) add("mp4")
        }
    }

    operator fun get(path: Path): FilePlaybackFactory {
        val extension = path.extension
        require(extension in supported) { "Unsupported file: $path" }
        return when (extension) {
            "avi" -> aviPlayback
            "mkv" -> mkvPlayback
            "mp4" -> mp4Playback
            else -> throw IllegalArgumentException()
        }
    }
}

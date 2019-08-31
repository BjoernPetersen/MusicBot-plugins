package net.bjoernpetersen.localmp3.provider

import com.mpatric.mp3agic.BaseException
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import net.bjoernpetersen.musicbot.api.player.song
import net.bjoernpetersen.musicbot.spi.image.AlbumArtSupplier
import net.bjoernpetersen.musicbot.spi.image.ImageData
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.streams.asSequence

@Suppress("TooManyFunctions")
class Mp3ProviderImpl : Mp3Provider, AlbumArtSupplier, CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val logger = KotlinLogging.logger { }

    private var folder: Config.SerializedEntry<Path>? = null
    private lateinit var recursive: Config.BooleanEntry
    @Inject
    private lateinit var playbackFactory: Mp3PlaybackFactory
    private lateinit var songById: Map<String, Song>

    private lateinit var customSubject: Config.StringEntry

    override val name = "Local MP3"
    override val description = "MP3s from some local directory"
    override val subject: String
        get() {
            val customSubject = if (this::customSubject.isInitialized) customSubject.get()
            else null
            return customSubject ?: folder?.get()?.fileName?.toString() ?: name
        }

    @UseExperimental(ExperimentalConfigDsl::class)
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        folder = config.serialized("folder") {
            description = "The folder the MP3s should be taken from"
            check { path ->
                if (path == null) "Required"
                else if (!Files.isDirectory(path)) "Not a directory"
                else null
            }
            serializer = PathSerializer
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

        return listOf(folder!!, recursive, customSubject)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Initializing...")
        val folder = folder?.get() ?: throw InitializationException()
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
            playbackFactory.createPlayback(song.id.toPath().toFile())
        }
    }

    private suspend fun initializeSongs(
        initWriter: InitStateWriter,
        root: Path,
        recursive: Boolean
    ): Map<String, Song> =
        (if (recursive) Files.walk(root).asSequence() else Files.list(root).asSequence())
            .filter { Files.isRegularFile(it) }
            .filter { it.extension.toLowerCase(Locale.US) == "mp3" }
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

    override fun getAlbumArt(songId: String): ImageData? {
        val path = songId.toPath()
        return loadImage(folder!!.get()!!, path)
    }

    private suspend fun createSong(path: Path): Song? {
        return withContext(coroutineContext) {
            val mp3 = try {
                Mp3File(path)
            } catch (e: IOException) {
                logger.error(e) { e.message ?: "Exception of type ${e::class.java.name}" }
                return@withContext null
            } catch (e: BaseException) {
                logger.error(e) { e.message ?: "Exception of type ${e::class.java.name}" }
                return@withContext null
            }

            val id3 = when {
                mp3.hasId3v1Tag() -> mp3.id3v1Tag
                mp3.hasId3v2Tag() -> mp3.id3v2Tag
                else -> return@withContext null
            }

            song(path.toId()) {
                title = id3.title
                description = id3.artist ?: ""
                duration = mp3.lengthInSeconds.toInt()
            }
        }
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

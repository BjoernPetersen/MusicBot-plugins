package net.bjoernpetersen.localmp3.provider

import com.mpatric.mp3agic.BaseException
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.song
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.image.AlbumArtSupplier
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.asSequence

class Index(
    private val provider: AlbumArtSupplier,
    indexDir: Path,
    private val songDir: Path
) : CoroutineScope by PluginScope(Dispatchers.IO), AutoCloseable {
    private val logger = KotlinLogging.logger { }
    private val db = IndexDb(provider, indexDir.resolve("index.db"))

    suspend fun load(writer: InitStateWriter, recursive: Boolean): Map<String, Song> {
        return withContext(coroutineContext) {
            writer.state("Loading songs from index...")
            val indexSongs = db.readAll()

            @Suppress("MagicNumber")
            val foundSongs = ConcurrentHashMap<String, Song>(maxOf(256, indexSongs.size * 2))

            writer.state("Loading songs from disk...")
            coroutineScope {
                (if (recursive) Files.walk(songDir) else Files.list(songDir))
                    .asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { it.extension.toLowerCase(Locale.US) == "mp3" }
                    .forEach { path ->
                        val id = path.toId()
                        val indexed = indexSongs[id]
                        if (indexed != null) foundSongs[id] = indexed
                        else launch {
                            val song = createSong(writer, path)
                            if (song != null) foundSongs[id] = song
                        }
                    }
            }

            startCleanup(indexSongs, foundSongs)

            foundSongs
        }
    }

    private fun startCleanup(
        indexSongs: Map<String, Song>,
        foundSongs: ConcurrentHashMap<String, Song>
    ) {
        launch {
            logger.debug { "Starting index cleanup..." }
            indexSongs.keys.asSequence()
                .filter { it !in foundSongs.keys }
                .forEach {
                    logger.debug { "Deleting $it" }
                    db.delete(it)
                }
        }
    }

    private fun createSong(initWriter: InitStateWriter, path: Path): Song? {
        logger.debug { "Loading tag for '$path'" }
        return createSong(path).also {
            if (it == null) {
                initWriter.warning("Could not load song from '$path'")
            } else {
                initWriter.state("""Loaded song ${it.title}""")
                launch {
                    logger.debug { "Writing to db: ${it.title}" }
                    db.write(it)
                }
            }
        }
    }

    @Suppress("ReturnCount")
    private fun createSong(path: Path): Song? {
        val mp3 = try {
            Mp3File(path)
        } catch (e: IOException) {
            logger.error(e) { e.message ?: "Exception of type ${e::class.java.name}" }
            null
        } catch (e: BaseException) {
            logger.error(e) { e.message ?: "Exception of type ${e::class.java.name}" }
            null
        } ?: return null

        val id3 = when {
            mp3.hasId3v1Tag() -> mp3.id3v1Tag
            mp3.hasId3v2Tag() -> mp3.id3v2Tag
            else -> return null
        }

        return provider.song(path.toId()) {
            title = id3.title
            description = id3.artist ?: ""
            duration = mp3.lengthInSeconds.toInt()
        }
    }

    override fun close() {
        val job = coroutineContext[Job] as CompletableJob
        job.complete()
        job.invokeOnCompletion {
            db.close()
            logger.debug { "Closed" }
        }
    }
}

@Suppress("MagicNumber")
private class IndexDb(
    private val provider: AlbumArtSupplier,
    indexFile: Path
) : AutoCloseable {
    private val logger = KotlinLogging.logger { }
    private val db: Connection by lazy {
        DriverManager.getConnection("jdbc:sqlite:$indexFile").also {
            it.createStatement().use { statement ->
                statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS songs(
                        id TEXT PRIMARY KEY UNIQUE NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        duration INT NOT NULL)
                    """.trimMargin()
                )
            }
        }
    }

    private val getAll: PreparedStatement by lazy {
        db.prepareStatement(
            """
                SELECT id, title, description, duration
                FROM songs
            """.trimIndent()
        )
    }

    private val insertSong: PreparedStatement by lazy {
        db.prepareStatement(
            """
                INSERT OR REPLACE INTO songs (id, title, description, duration)
                VALUES(?,?,?,?)
            """.trimIndent()
        )
    }

    private val deleteSong: PreparedStatement by lazy {
        db.prepareStatement(
            """
                DELETE FROM songs
                WHERE id = ?
            """.trimIndent()
        )
    }

    fun readAll(): Map<String, Song> {
        return synchronized(getAll) {
            try {
                val result = getAll.executeQuery()
                val songById = HashMap<String, Song>(256)
                while (result.next()) {
                    val song = provider.song(result.getString("id")) {
                        title = result.getString("title")
                        description = result.getString("description")
                        val duration = result.getInt("duration")
                        if (duration != 0) {
                            this.duration = duration
                        }
                    }
                    songById[song.id] = song
                }
                songById
            } catch (e: SQLException) {
                logger.error(e) { "Could not get all songs" }
                emptyMap()
            }
        }
    }

    fun write(song: Song) {
        synchronized(insertSong) {
            try {
                insertSong.clearParameters()
                insertSong.setString(1, song.id)
                insertSong.setString(2, song.title)
                insertSong.setString(3, song.description)
                insertSong.setInt(4, song.duration ?: 0)
                insertSong.executeUpdate()
            } catch (e: SQLException) {
                logger.error(e) { "Could not write song to index" }
            }
        }
    }

    fun delete(id: String) {
        synchronized(deleteSong) {
            try {
                deleteSong.clearParameters()
                deleteSong.setString(1, id)
                deleteSong.executeUpdate()
            } catch (e: SQLException) {
                logger.error(e) { "Could not delete song from index" }
            }
        }
    }

    override fun close() {
        db.close()
    }
}

package net.bjoernpetersen.spotify.suggester

import com.wrapper.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.ChoiceBox
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.spotify.auth.SpotifyAuthenticator
import net.bjoernpetersen.spotify.marketFromToken
import net.bjoernpetersen.spotify.provider.SpotifyProvider
import java.io.IOException
import java.util.Collections
import java.util.LinkedList
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@IdBase("Spotify playlist")
class PlaylistSuggester : Suggester, CoroutineScope {

    private val logger = KotlinLogging.logger {}

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private lateinit var userId: Config.StringEntry
    private lateinit var playlistId: Config.SerializedEntry<PlaylistChoice>
    private lateinit var shuffle: Config.BooleanEntry

    @Inject
    private lateinit var auth: SpotifyAuthenticator
    @Inject
    private lateinit var provider: SpotifyProvider

    private var playlist: PlaylistChoice? = null
    private lateinit var playlistSongs: List<Song>

    private var nextIndex: Int = 0
    private var nextSongs: LinkedList<Song> = LinkedList()

    override val name: String = "Spotify playlist"
    override val description: String = "Plays songs from one of your public Spotify playlists"
    override val subject: String
        get() = playlist?.displayName ?: name

    private suspend fun findPlaylists(): List<PlaylistChoice>? {
        return withContext(coroutineContext) {
            var userId = userId.get()
            if (userId == null) {
                logger.debug("No userId set, trying to retrieve it...")
                userId = try {
                    loadUserId()
                } catch (e: InitializationException) {
                    logger.info("user ID could not be found.")
                    return@withContext null
                }
                this@PlaylistSuggester.userId.set(userId)
            }
            val playlists = try {
                getApi()
                    .getListOfUsersPlaylists(userId)
                    .limit(50)
                    .build()
                    .execute()
            } catch (e: Throwable) {
                logger.error(e) { "Could not retrieve playlists" }
                return@withContext null
            }

            playlists.items.map {
                PlaylistChoice(it.id, it.name)
            }
        }
    }

    override suspend fun suggestNext(): Song = withContext(coroutineContext) {
        val song = getNextSuggestions(1)[0]
        removeSuggestion(song)
        song
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> =
        withContext(coroutineContext) {
            val startIndex = nextIndex
            while (nextSongs.size < Math.max(Math.min(50, maxLength), 1)) {
                // load more suggestions
                nextSongs.add(playlistSongs[nextIndex])
                nextIndex = (nextIndex + 1) % playlistSongs.size
                if (nextIndex == startIndex) {
                    // the playlist is shorter than maxLength
                    break
                }
            }
            Collections.unmodifiableList(nextSongs)
        }

    override suspend fun removeSuggestion(song: Song) = withContext<Unit>(coroutineContext) {
        nextSongs.remove(song)
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        shuffle = config.BooleanEntry(
            "shuffle",
            "Whether the playlist should be shuffled",
            true
        )
        playlistId = config.SerializedEntry(
            "playlist",
            "One of your public playlists to play",
            PlaylistChoice.Serializer,
            NonnullConfigChecker,
            ChoiceBox(PlaylistChoice::displayName, { findPlaylists() }, true)
        )
        return listOf(shuffle, playlistId)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        userId = secrets.StringEntry(
            "userId",
            "",
            NonnullConfigChecker,
            null
        )
        return emptyList()
    }

    override fun createStateEntries(state: Config) {}

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        withContext(coroutineContext) {
            initStateWriter.state("Loading user ID")
            if (userId.get() == null) {
                userId.set(loadUserId())
            }

            initStateWriter.state("Loading playlist songs")
            playlist = playlistId.get()
            playlistSongs = playlist?.id?.let { playlistId ->
                loadPlaylist(playlistId)
                    .let { if (shuffle.get()) it.shuffled() else it }
                    .also { logger.info { "Loaded ${it.size} songs" } }
            } ?: throw InitializationException("No playlist selected")

            nextSongs = LinkedList()
        }
    }

    private suspend fun getApi(): SpotifyApi {
        val token = auth.getToken()
        return SpotifyApi.builder()
            .setAccessToken(token)
            .build()
    }

    @Throws(InitializationException::class)
    private suspend fun loadUserId(): String {
        try {
            return getApi().currentUsersProfile
                .build()
                .execute()
                .id
        } catch (e: Exception) {
            throw InitializationException("Could not get user ID", e)
        }
    }

    @Throws(InitializationException::class)
    private suspend fun loadPlaylist(playlistId: String, offset: Int = 0): List<Song> {
        val playlistTracks = try {
            getApi()
                .getPlaylistsTracks(playlistId)
                .marketFromToken()
                .offset(offset)
                .build()
                .execute()
        } catch (e: Exception) {
            throw InitializationException("Could not load playlist", e)
        }

        val ids = playlistTracks.items
            .asSequence()
            .map { it.track }
            .filter { it.isPlayable }
            .map { it.id }
            .toList()

        val result = provider.lookupBatch(ids)

        return if (playlistTracks.next == null) result
        else result + loadPlaylist(playlistId, offset + playlistTracks.items.size)
    }

    @Throws(IOException::class)
    override suspend fun close() {
        job.cancel()
        nextSongs.clear()
    }
}

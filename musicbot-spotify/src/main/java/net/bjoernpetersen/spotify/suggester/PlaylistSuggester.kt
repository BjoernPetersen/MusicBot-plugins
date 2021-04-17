package net.bjoernpetersen.spotify.suggester

import com.wrapper.spotify.SpotifyApi
import com.wrapper.spotify.exceptions.SpotifyWebApiException
import com.wrapper.spotify.model_objects.specification.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.config.choiceBox
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.BrokenSuggesterException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyProvider
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyScope
import net.bjoernpetersen.spotify.marketFromToken
import java.io.IOException
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@Suppress("TooManyFunctions")
@IdBase("Spotify playlist")
class PlaylistSuggester : Suggester, CoroutineScope by PluginScope(Dispatchers.IO) {

    private val logger = KotlinLogging.logger {}

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
    override val description: String = "Plays songs from one of your Spotify playlists"
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
                    .limit(SPOTIFY_REQUEST_LIMIT)
                    .build()
                    .execute()
            } catch (e: IOException) {
                logger.error(e) { "Could not retrieve playlists" }
                return@withContext null
            } catch (e: SpotifyWebApiException) {
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
            if (playlistSongs.isEmpty()) throw BrokenSuggesterException("Empty playlist")
            val startIndex = nextIndex
            while (nextSongs.size < max(min(SUGGESTIONS_LIMIT, maxLength), 1)) {
                // load more suggestions
                nextSongs.add(playlistSongs[nextIndex])
                nextIndex = (nextIndex + 1) % playlistSongs.size
                if (nextIndex == startIndex) {
                    // the playlist is shorter than maxLength
                    break
                }
            }
            nextSongs.toList()
        }

    override suspend fun removeSuggestion(song: Song) = withContext<Unit>(coroutineContext) {
        nextSongs.remove(song)
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        shuffle = config.boolean("shuffle") {
            description = "Whether the playlist should be shuffled"
            default = true
        }
        playlistId = config.serialized("playlist") {
            description = "One of your public playlists to play"
            serializer = PlaylistChoice
            check(NonnullConfigChecker)
            choiceBox {
                describe { it.displayName }
                lazy()
                refresh {
                    findPlaylists()?.sortedBy { it.displayName }
                }
            }
        }
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

    override fun createStateEntries(state: Config) {
        auth.requireScopes(
            SpotifyScope.PLAYLIST_READ_PRIVATE,
            SpotifyScope.PLAYLIST_READ_COLLABORATIVE
        )
    }

    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        withContext(coroutineContext) {
            progressFeedback.state("Loading user ID")
            if (userId.get() == null) {
                userId.set(loadUserId())
            }

            progressFeedback.state("Loading playlist songs")
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
        val error = try {
            return getApi().currentUsersProfile
                .build()
                .execute()
                .id
        } catch (e: IOException) {
            e
        } catch (e: SpotifyWebApiException) {
            e
        }

        throw InitializationException("Could not load user ID", error)
    }

    @Throws(InitializationException::class)
    private suspend fun loadPlaylist(playlistId: String, offset: Int = 0): List<Song> {
        val playlistTracks = try {
            getApi()
                .getPlaylistsItems(playlistId)
                .marketFromToken()
                .offset(offset)
                .build()
                .execute()
        } catch (e: IOException) {
            throw InitializationException("IO error during playlist loading", e)
        } catch (e: SpotifyWebApiException) {
            throw InitializationException("API error during playlist loading", e)
        }

        val ids = playlistTracks.items
            .asSequence()
            .map { it.track as? Track }
            .filterNotNull()
            .filter { it.isPlayable ?: false }
            .map { it.id }
            .toList()

        val result = provider.lookupBatch(ids)

        return if (playlistTracks.next == null) result
        else result + loadPlaylist(playlistId, offset + playlistTracks.items.size)
    }

    @Throws(IOException::class)
    override suspend fun close() {
        run { cancel() }
        nextSongs.clear()
    }

    private companion object {
        const val SPOTIFY_REQUEST_LIMIT = 50
        const val SUGGESTIONS_LIMIT = 50
    }
}

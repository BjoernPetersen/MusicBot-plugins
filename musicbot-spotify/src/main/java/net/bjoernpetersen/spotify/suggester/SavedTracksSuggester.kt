package net.bjoernpetersen.spotify.suggester

import com.wrapper.spotify.SpotifyApi
import com.wrapper.spotify.exceptions.SpotifyWebApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.io.errors.IOException
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ExperimentalConfigDsl
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.BrokenSuggesterException
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyProvider
import net.bjoernpetersen.spotify.marketFromToken
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@Suppress("TooManyFunctions")
@UseExperimental(ExperimentalConfigDsl::class)
@IdBase("Spotify saved tracks")
class SavedTracksSuggester : Suggester, CoroutineScope by PluginScope(Dispatchers.IO) {
    private val logger = KotlinLogging.logger {}

    override val name: String = "Spotify saved tracks"
    override val description: String = "Plays your saved/liked tracks on Spotify"
    override val subject: String
        get() = name

    private lateinit var userId: Config.StringEntry
    private lateinit var shuffle: Config.BooleanEntry

    @Inject
    private lateinit var auth: SpotifyAuthenticator
    @Inject
    private lateinit var provider: SpotifyProvider

    private lateinit var playlistSongs: List<Song>

    private var nextIndex: Int = 0
    private var nextSongs: LinkedList<Song> = LinkedList()

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        shuffle = config.boolean("shuffle") {
            description = "Whether the playlist should be shuffled"
            default = true
        }
        return listOf(shuffle)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        userId = secrets.string("userId") {
            description = ""
            check(NonnullConfigChecker)
        }
        return emptyList()
    }

    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        withContext(coroutineContext) {
            initStateWriter.state("Loading user ID")
            if (userId.get() == null) {
                userId.set(loadUserId())
            }

            initStateWriter.state("Loading saved tracks")
            playlistSongs = loadPlaylist()
                .let { if (shuffle.get()) it.shuffled() else it }
                .also { logger.info { "Loaded ${it.size} songs" } }
        }

        nextSongs = LinkedList()
    }

    private suspend fun getApi(): SpotifyApi {
        val token = auth.getToken()
        return SpotifyApi.builder()
            .setAccessToken(token)
            .build()
    }

    @Throws(InitializationException::class)
    private suspend fun loadPlaylist(offset: Int = 0): List<Song> {
        val playlistTracks = try {
            getApi()
                .usersSavedTracks
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
            .map { it.track }
            .filter { it?.isPlayable ?: false }
            .map { it.id }
            .toList()

        val result = provider.lookupBatch(ids)

        return if (playlistTracks.next == null) result
        else result + loadPlaylist(offset + playlistTracks.items.size)
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

    override suspend fun suggestNext(): Song = withContext(coroutineContext) {
        val song = getNextSuggestions(1)[0]
        removeSuggestion(song)
        song
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> =
        withContext(coroutineContext) {
            if (playlistSongs.isEmpty()) throw BrokenSuggesterException("No saved tracks")
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

    @Throws(IOException::class)
    override suspend fun close() {
        run { cancel() }
        nextSongs.clear()
    }

    private companion object {
        const val SUGGESTIONS_LIMIT = 50
    }
}

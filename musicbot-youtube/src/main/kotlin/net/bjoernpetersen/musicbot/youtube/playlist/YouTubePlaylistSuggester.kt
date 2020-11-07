package net.bjoernpetersen.musicbot.youtube.playlist

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubeAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubeProvider
import javax.inject.Inject

@IdBase("YouTube playlist")
class YouTubePlaylistSuggester : Suggester, CoroutineScope by PluginScope(Dispatchers.IO) {
    override val name: String = "Official"

    private var playlistName: String? = null

    override val subject: String
        get() = playlistName ?: "YouTube playlist"
    override val description: String = "Suggests videos from a YouTube playlist"

    @Inject
    private lateinit var auth: YouTubeAuthenticator

    @Inject
    private lateinit var provider: YouTubeProvider
    private lateinit var api: YouTube

    private lateinit var config: YouTubePlaylistSuggesterConfig

    private lateinit var playlist: Playlist<String>

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        this.config = YouTubePlaylistSuggesterConfig(config)
        return this.config.entries
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        return emptyList()
    }

    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        withContext(coroutineContext) {

            api = YouTube
                .Builder(
                    NetHttpTransport(),
                    JacksonFactory.getDefaultInstance(),
                    null
                )
                .setApplicationName("music-bot")
                .build()

            config.playlistId.checkError()?.let {
                throw InitializationException("Invalid playlist ID: $it")
            }

            val playlistId = config.playlistId.get()!!
            initStateWriter.state("Loading playlist info")
            val playlistResponse = api.Playlists().list(PLAYLIST_PARTS).apply {
                key = auth.getToken()
                id = playlistId
                maxResults = 1
            }.execute()

            val items = playlistResponse.items
            if (items.isEmpty()) {
                throw InitializationException("Playlist not found: $playlistId")
            }

            val resultPlaylist = items.first()
            playlistName = resultPlaylist.snippet.title
            val itemCount = resultPlaylist.contentDetails.itemCount.toInt()
            if (itemCount == 0) {
                throw InitializationException("Playlist is empty")
            }
            playlist = Playlist(
                playlistId,
                itemCount,
                ::loadPlaylistItems
            )

            if (config.shuffle.get()) {
                initStateWriter.state("Shuffling playlist (may take a while)")
                playlist.shuffle()
            }
        }
    }

    private suspend fun loadPlaylistItems(token: String?): Page<String> {
        val response = api.PlaylistItems().list(ITEM_PARTS).apply {
            key = auth.getToken()
            maxResults = PLAYLIST_LIST_MAX_RESULTS
            playlistId = playlist.id
            pageToken = token
        }.execute()
        val nextToken = response.nextPageToken
        val songs = provider.lookupBatch(response.items.map { it.contentDetails.videoId })
        return Page(nextToken, songs)
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        return playlist.getNextSuggestions(maxLength)
    }

    override suspend fun removeSuggestion(song: Song) {
        playlist.removeSuggestion(song)
    }

    override suspend fun suggestNext(): Song {
        return playlist.suggestNext()
    }

    override suspend fun close() {
        run { cancel() }
    }

    private companion object {
        const val PLAYLIST_PARTS = "snippet,contentDetails"
        const val ITEM_PARTS = "id,contentDetails"
        const val PLAYLIST_LIST_MAX_RESULTS = 50L
    }
}

private data class Page<PagingToken>(
    val token: PagingToken?,
    val items: List<Song>
)

private class Playlist<PagingToken>(
    val id: String,
    itemCount: Int,
    private val loadItems: suspend (token: PagingToken?) -> Page<PagingToken>
) {
    private var allLoaded = false
    private var nextToken: PagingToken? = null
    private val allSongs: MutableList<Song> = ArrayList(itemCount)
    private val remaining: MutableList<Song> = ArrayList(itemCount)

    fun removeSuggestion(song: Song) {
        remaining.remove(song)
    }

    private suspend fun loadNext(): List<Song> {
        val page = loadItems(nextToken)
        nextToken = page.token
        if (nextToken == null) {
            allLoaded = true
        }
        allSongs.addAll(page.items)
        return page.items
    }

    suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        while (!allLoaded && remaining.size < maxLength) {
            val loaded = loadNext()
            remaining.addAll(loaded)
        }
        if (allLoaded && remaining.isEmpty()) {
            remaining.clear()
            remaining.addAll(allSongs)
        }
        return remaining.subList(0, maxLength)
    }

    suspend fun suggestNext(): Song {
        getNextSuggestions(1)
        return remaining.removeAt(0)
    }

    suspend fun shuffle() {
        while (!allLoaded) {
            loadNext()
        }
        allSongs.shuffle()
    }
}

private const val PLAYLIST_ID_LENGTH = 34

private class YouTubePlaylistSuggesterConfig(config: Config) {
    val shuffle by config.boolean {
        description = "Shuffle the playlist items (avoid for huge playlists)"
        default = false
    }
    val playlistId by config.string {
        description = "A YouTube playlist ID"
        check { if (it?.length != PLAYLIST_ID_LENGTH) "Enter a valid ID" else null }
        uiNode = TextBox
    }

    val entries: List<Config.Entry<*>>
        get() = listOf(playlistId, shuffle)
}

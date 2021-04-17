package net.bjoernpetersen.musicbot.youtube.provider

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.SearchResult
import com.google.api.services.youtube.model.Video
import com.google.common.collect.Lists
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.song
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubeAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubePlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubeProvider
import net.bjoernpetersen.musicbot.youtube.cache.AsyncLoader
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@Suppress("TooManyFunctions")
class YouTubeProviderImpl : YouTubeProvider, CoroutineScope by PluginScope(Dispatchers.IO) {
    override val name: String
        get() = "Official"
    override val description: String
        get() = "Uses the YouTube Data API v3"
    override val subject: String
        get() = "YouTube"

    private val logger = KotlinLogging.logger { }

    @Inject
    private lateinit var playback: YouTubePlaybackFactory

    @Inject
    private lateinit var auth: YouTubeAuthenticator
    private lateinit var api: YouTube

    private lateinit var songCache: LoadingCache<String, Deferred<Song?>>
    private lateinit var searchCache: LoadingCache<String, Deferred<List<Song>?>>

    override fun createStateEntries(state: Config) = Unit
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()
    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    @Suppress("MagicNumber")
    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        progressFeedback.state("Creating API access object")
        api = YouTube
            .Builder(
                NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                null
            )
            .setApplicationName("music-bot")
            .build()

        songCache = Caffeine.newBuilder()
            .initialCapacity(128)
            .maximumSize(2048)
            .build(
                AsyncLoader(this) {
                    lookupSong(it)
                }
            )

        searchCache = Caffeine.newBuilder()
            .initialCapacity(128)
            .maximumSize(512)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(AsyncLoader(this) { actualSearch(it, 0) })
    }

    override suspend fun lookupBatch(ids: List<String>): List<Song> {
        val result = Array<Deferred<Song?>?>(ids.size) { null }
        val toBeLookedUp = ArrayList<IndexedValue<String>>(ids.size)

        ids.forEachIndexed { index, id ->
            val cached = songCache.getIfPresent(id)
            if (cached != null) result[index] = cached
            else toBeLookedUp.add(IndexedValue(index, id))
        }

        if (toBeLookedUp.isNotEmpty()) {
            logger.debug { "Looking up songs, size: ${toBeLookedUp.size}" }

            try {
                withContext(coroutineContext) {
                    for (
                        partition: List<IndexedValue<String>> in Lists.partition(
                            toBeLookedUp,
                            BATCH_LOOKUP_MAX
                        )
                    ) {
                        val idsString = partition.map { pair -> pair.value }

                        val videos: List<Video> = api.videos().list(VIDEO_RESULT_PARTS)
                            .setKey(auth.getToken())
                            .setId(idsString)
                            .execute()
                            .items

                        if (videos.size != partition.size) {
                            logger.warn {
                                "Batch lookup result (${videos.size}) differs " +
                                    "from request (${partition.size})"
                            }
                        }

                        val indexById = partition.associateBy({ it.value }, { it.index })
                        for (video in videos) {
                            val song = createSong(video)
                            val deferredSong = CompletableDeferred(song)
                            val index = indexById[video.id]
                            if (index == null) {
                                logger.warn { "Unexpected lookup result: $video.id" }
                                continue
                            }
                            result[index] = deferredSong
                            songCache.put(song.id, deferredSong)
                        }
                    }
                }
            } catch (e: IOException) {
                logger.error(e) { "IOException during video lookup" }
                return emptyList()
            }
        }

        return withContext(coroutineContext) {
            result.mapNotNull { it?.await() }
        }
    }

    private fun createSong(video: Video): Song {
        val snippet = video.snippet
        val medium = snippet.thumbnails.medium
        return song(video.id) {
            title = snippet.title
            description = snippet.description
            duration = getDuration(video.contentDetails.duration)
            medium?.url?.let(::serveRemoteImage)
        }
    }

    override suspend fun search(query: String, offset: Int): List<Song> {
        val trimmedQuery = query.trim()
        return when {
            trimmedQuery.isEmpty() -> emptyList()
            offset == 0 -> searchCache.get(trimmedQuery)?.await() ?: emptyList()
            else -> actualSearch(trimmedQuery, offset).also {
                searchCache.put(trimmedQuery, CompletableDeferred(it))
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun actualSearch(query: String, offset: Int): List<Song> {
        if (query.isBlank()) {
            return emptyList()
        }

        logger.debug { "Actually searching with offset $offset: $query" }

        val searchResults: List<SearchResult> = try {
            withContext(coroutineContext) {
                api.search().list(SEARCH_RESULT_PARTS)
                    .setKey(auth.getToken())
                    .setQ(query)
                    .setType(SEARCH_TYPE)
                    .setMaxResults(BATCH_LOOKUP_MAX.toLong())
                    .execute()
                    .items
            }
        } catch (e: IOException) {
            logger.error(e) { "IOException during search" }
            if (e.message?.contains("quota", ignoreCase = true) == true) {
                auth.invalidateToken()
            }
            return emptyList()
        }

        val fixedResults = searchResults
            .subList(
                max(0, min(offset, searchResults.size - 1)),
                min(searchResults.size, BATCH_LOOKUP_MAX)
            )
            .filter { s -> s.id.videoId != null }

        return lookupBatch(fixedResults.map { it.id.videoId })
    }

    override suspend fun lookup(id: String): Song {
        return songCache.get(id)?.await() ?: throw NoSuchSongException(id)
    }

    private suspend fun lookupSong(id: String): Song? {
        logger.debug { "Looking up ID $id" }

        val results: List<Video> = try {
            withContext(coroutineContext) {
                api.videos().list(VIDEO_RESULT_PARTS)
                    .setKey(auth.getToken())
                    .setId(listOf(id))
                    .execute()
                    .items
            }
        } catch (e: IOException) {
            logger.error(e) { "Error looking up song" }
            if (e.message?.contains("quota", ignoreCase = true) == true) {
                auth.invalidateToken()
            }
            emptyList()
        }

        if (results.isEmpty()) {
            return null
        }

        val result = results.firstOrNull()
        return if (result?.id != id) null
        else createSong(result)
    }

    override suspend fun loadSong(song: Song): Resource {
        return playback.load(song.id)
    }

    override suspend fun supplyPlayback(song: Song, resource: Resource): Playback {
        return playback.createPlayback(song.id, resource)
    }

    override suspend fun close() {
        run { cancel() }
    }

    private companion object {
        val SEARCH_RESULT_PARTS = listOf("id")
        val VIDEO_RESULT_PARTS = listOf("id", "snippet", "contentDetails")
        val SEARCH_TYPE = listOf("video")

        const val BATCH_LOOKUP_MAX = 50
    }
}

private fun getDuration(encodedDuration: String): Int {
    return Duration.parse(encodedDuration).seconds.toInt()
}

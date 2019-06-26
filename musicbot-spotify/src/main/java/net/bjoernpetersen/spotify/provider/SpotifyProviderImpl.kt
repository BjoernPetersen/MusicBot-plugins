package net.bjoernpetersen.spotify.provider

import com.google.common.cache.CacheBuilder
import com.google.common.cache.LoadingCache
import com.google.common.collect.Lists
import com.wrapper.spotify.SpotifyApi
import com.wrapper.spotify.exceptions.SpotifyWebApiException
import com.wrapper.spotify.model_objects.specification.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.cache.AsyncLoader
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.spotify.auth.SpotifyAuthenticator
import net.bjoernpetersen.spotify.marketFromToken
import net.bjoernpetersen.spotify.playback.SpotifyPlaybackFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class SpotifyProviderImpl : SpotifyProvider, CoroutineScope {

    private val logger = KotlinLogging.logger {}
    @Inject
    private lateinit var authenticator: SpotifyAuthenticator
    @Inject
    private lateinit var spotifyPlaybackFactory: SpotifyPlaybackFactory

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    override val name: String = "Spotify"
    override val description: String = "Provides songs from Spotify"
    override val subject: String = "Spotify"

    private lateinit var songCache: LoadingCache<String, Deferred<Song>>

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()
    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    private suspend fun getApi(): SpotifyApi {
        return SpotifyApi.builder()
            .setAccessToken(authenticator.getToken())
            .build()
    }

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Trying to access API")
        getApi()

        songCache = CacheBuilder.newBuilder()
            .initialCapacity(512)
            .maximumSize(2048)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build(AsyncLoader(this) { actualLookup(it) })
    }

    override suspend fun supplyPlayback(song: Song, resource: Resource): Playback {
        return spotifyPlaybackFactory.getPlayback(song.id)
    }

    override suspend fun loadSong(song: Song): Resource = NoResource

    override suspend fun close() {
        job.cancel()
    }

    private fun createSong(
        id: String,
        title: String,
        description: String,
        durationMs: Int,
        albumArtUrl: String?
    ): Song {
        return Song(
            id = id,
            provider = this,
            title = title,
            description = description,
            duration = durationMs / 1000,
            albumArtUrl = albumArtUrl
        )
    }

    override suspend fun search(query: String, offset: Int): List<Song> {
        if (query.isEmpty()) {
            return emptyList()
        }
        return withContext(coroutineContext) {
            try {
                getApi().searchTracks(query)
                    .limit(40)
                    .offset(offset)
                    .marketFromToken()
                    .build()
                    .execute()
                    .items
                    .map(::trackToSong)
                    .also { songs ->
                        songs.forEach {
                            songCache.put(it.id, CompletableDeferred(it))
                        }
                    }
            } catch (e: IOException) {
                logger.error(e) { "Error searching for spotify songs (query: $query)" }
                emptyList<Song>()
            } catch (e: SpotifyWebApiException) {
                logger.error(e) { "Error searching for spotify songs (query: $query)" }
                emptyList<Song>()
            }
        }
    }

    override fun trackToSong(track: Track): Song {
        val id = track.id
        val title = track.name
        val description = track.artists.asSequence()
            .map { it.name }
            .joinToString()
        val durationMs = track.durationMs
        val images = track.album.images
        val albumArtUrl = if (images.isEmpty()) null else images[0].url
        return createSong(id, title, description, durationMs, albumArtUrl)
    }

    @Throws(NoSuchSongException::class)
    override suspend fun lookup(id: String): Song {
        return withContext(coroutineContext) {
            songCache[id].await()
        }
    }

    @Throws(NoSuchSongException::class)
    private suspend fun actualLookup(id: String): Song {
        logger.debug { "Looking up song with ID $id" }
        try {
            return trackToSong(
                getApi()
                    .getTrack(id)
                    .build()
                    .execute()
            )
        } catch (e: IOException) {
            throw NoSuchSongException("Error looking up song: $id", SpotifyProviderImpl::class, e)
        } catch (e: SpotifyWebApiException) {
            throw NoSuchSongException("Error looking up song: $id", SpotifyProviderImpl::class, e)
        }
    }

    override suspend fun lookupBatch(ids: List<String>): List<Song> {
        return withContext(coroutineContext) {
            val result = Array(ids.size) { songCache.getIfPresent(ids[it]) }

            val missingIds = ids.withIndex()
                .filter { result[it.index] == null }

            logger.debug { "Loading ${missingIds.size} of ${ids.size} requested songs" }

            for (subIds in Lists.partition(missingIds, 50)) {
                try {
                    getApi()
                        .getSeveralTracks(*subIds.map { it.value }.toTypedArray())
                        .build()
                        .execute()
                        .map { trackToSong(it) }
                        .forEach { songCache.put(it.id, CompletableDeferred(it)) }
                } catch (e: IOException) {
                    logger.info(e) { "Could not look up some ID." }
                } catch (e: SpotifyWebApiException) {
                    logger.info(e) { "Could not look up some ID." }
                }

                subIds.forEach { (index, value) ->
                    result[index] = songCache.get(value)!!
                }
            }
            result.map { it!!.await() }
        }
    }
}

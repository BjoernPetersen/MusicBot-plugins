package net.bjoernpetersen.spotify.playback

import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.io.errors.IOException
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyScope
import net.bjoernpetersen.spotify.control.SpotifyControl

class WebApiSpotifyPlaybackFactory : SpotifyPlaybackFactory, CoroutineScope {

    private val logger = KotlinLogging.logger { }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    @Inject
    private lateinit var authenticator: SpotifyAuthenticator
    @Inject
    private lateinit var control: SpotifyControl

    override val name: String = "Remote control"
    override val description: String =
        "Remotely controls an official client. Requires a Spotify Premium subscription."

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()
    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) {
        authenticator.requireScopes(
            SpotifyScope.USER_MODIFY_PLAYBACK_STATE,
            SpotifyScope.USER_READ_PLAYBACK_STATE
        )
    }

    @Throws(InitializationException::class)
    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Checking authentication")
        try {
            authenticator.getToken()
        } catch (e: IOException) {
            throw InitializationException("Authentication error", e)
        } catch (e: KotlinNullPointerException) {
            throw InitializationException("Not authenticated", e)
        }
    }

    override suspend fun loadSong(songId: String): Resource = NoResource

    override suspend fun getPlayback(songId: String, resource: Resource): Playback {
        return SpotifyPlayback(authenticator, control.deviceId, songId)
    }

    override suspend fun close() {
        job.cancel()
    }
}

package com.github.felixgail.musicbot.gplaymusic

import com.github.felixgail.gplaymusic.api.GPlayMusic
import com.github.felixgail.gplaymusic.api.StationApi
import com.github.felixgail.gplaymusic.api.TrackApi
import com.github.felixgail.gplaymusic.util.TokenProvider
import com.google.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.plugin.Base
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.GenericPlugin
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.gplaymusic.GPlayMusicAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubeProvider
import com.github.felixgail.gplaymusic.exceptions.InitializationException as GPlayMusicInitializationException

@Base
class GPlayMusicApi : GenericPlugin, CoroutineScope by PluginScope(Dispatchers.IO) {
    override val name: String = "gplaymusic"

    override val description: String = "The gplaymusic API wrapper by FelixGail"

    @Inject
    private lateinit var auth: GPlayMusicAuthenticator
    @Inject(optional = true)
    private var youtubeProvider: YouTubeProvider? = null

    private lateinit var showVideos: Config.BooleanEntry

    lateinit var api: GPlayMusic
    val trackApi: TrackApi
        get() = api.trackApi
    val stationApi: StationApi
        get() = api.stationApi

    fun isYoutubeEnabled() = showVideos.get() && youtubeProvider != null

    override fun createStateEntries(state: Config) = Unit

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        showVideos = config.boolean("Show YouTube videos") {
            description = "Use YouTube video, if available"
            default = false
        }

        return listOf(showVideos)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        progressFeedback.state("Trying to log in...")

        api = try {
            createApi()
        } catch (e: GPlayMusicInitializationException) {
            progressFeedback.warning("Could not log in, trying to retrieve new token")
            auth.invalidateToken()
            try {
                createApi()
            } catch (e: GPlayMusicInitializationException) {
                throw InitializationException(e)
            }
        }
    }

    @Throws(GPlayMusicInitializationException::class)
    private suspend fun createApi(): GPlayMusic {
        return GPlayMusic.Builder()
            .setAuthToken(TokenProvider.provideToken(auth.getToken()))
            .build()
    }

    suspend fun refreshToken() {
        auth.invalidateToken()
        withContext(Dispatchers.IO) {
            api.changeToken(TokenProvider.provideToken(auth.getToken()))
        }
    }

    override suspend fun close() = Unit
}

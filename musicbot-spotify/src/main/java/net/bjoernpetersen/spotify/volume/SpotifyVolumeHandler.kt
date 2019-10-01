package net.bjoernpetersen.spotify.volume

import com.wrapper.spotify.SpotifyApi
import com.wrapper.spotify.exceptions.SpotifyWebApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.io.errors.IOException
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.plugin.volume.Volume
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyScope
import net.bjoernpetersen.musicbot.spi.plugin.volume.VolumeHandler
import net.bjoernpetersen.spotify.control.SpotifyControl
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class SpotifyVolumeHandler : VolumeHandler, CoroutineScope {
    private val logger = KotlinLogging.logger { }

    override val name: String = "Spotify client volume"
    override val description: String = "Remotely controls the volume of a Spotify client"

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    @Inject
    private lateinit var auth: SpotifyAuthenticator
    @Inject
    private lateinit var control: SpotifyControl

    private suspend fun getApi(): SpotifyApi = SpotifyApi.builder()
        .setAccessToken(auth.getToken())
        .build()

    override suspend fun getVolume(): Int {
        return withContext(coroutineContext) {
            try {
                getApi()
                    .usersAvailableDevices
                    .build()
                    .execute()
                    ?.find { control.deviceId == it.id }
                    ?.volume_percent
            } catch (e: IOException) {
                null
            } catch (e: SpotifyWebApiException) {
                logger.debug(e) {}
                null
            } ?: Volume.MAX
        }
    }

    override suspend fun setVolume(value: Int) {
        withContext(coroutineContext) {
            try {
                getApi()
                    .setVolumeForUsersPlayback(value)
                    .device_id(control.deviceId)
                    .build()
                    .execute()
            } catch (e: IOException) {
                logger.debug(e) {}
            } catch (e: SpotifyWebApiException) {
                logger.debug(e) {}
            }
        }
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override fun createStateEntries(state: Config) {
        auth.requireScopes(
            SpotifyScope.USER_READ_PLAYBACK_STATE,
            SpotifyScope.USER_MODIFY_PLAYBACK_STATE
        )
    }

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Trying to access client...")
        val volume = try {
            getVolume()
        } catch (e: IOException) {
            throw InitializationException(e)
        } catch (e: SpotifyWebApiException) {
            throw InitializationException(e)
        }
        initStateWriter.state("Volume is $volume")
    }

    override suspend fun close() {
        job.cancel()
    }
}

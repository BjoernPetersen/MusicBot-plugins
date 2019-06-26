package net.bjoernpetersen.spotify.volume

import com.wrapper.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.volume.VolumeHandler
import net.bjoernpetersen.spotify.auth.SpotifyAuthenticator
import net.bjoernpetersen.spotify.control.SpotifyControl
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class SpotifyVolumeHandler : VolumeHandler, CoroutineScope {
    private val logger = KotlinLogging.logger { }

    override val name: String = "Spotify volume handler"
    override val description: String = "Controls the volume of a Spotify client"

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
            } catch (e: Exception) {
                null
            } ?: 100
        }
    }

    override suspend fun setVolume(value: Int) {
        withContext(coroutineContext) {
            getApi()
                .setVolumeForUsersPlayback(value)
                .device_id(control.deviceId)
                .build()
                .execute()
        }
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override fun createStateEntries(state: Config) {}

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Trying to access client...")
        val volume = try {
            getVolume()
        } catch (e: Exception) {
            throw InitializationException(e)
        }
        initStateWriter.state("Volume is $volume")
    }

    override suspend fun close() {
        job.cancel()
    }
}

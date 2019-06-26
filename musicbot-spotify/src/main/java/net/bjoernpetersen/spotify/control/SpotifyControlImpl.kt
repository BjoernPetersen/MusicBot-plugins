package net.bjoernpetersen.spotify.control

import com.wrapper.spotify.SpotifyApi
import com.wrapper.spotify.model_objects.miscellaneous.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.ChoiceBox
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.spotify.auth.SpotifyAuthenticator
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class SpotifyControlImpl : SpotifyControl, CoroutineScope {

    private val logger = KotlinLogging.logger { }

    @Inject
    private lateinit var authenticator: SpotifyAuthenticator
    private lateinit var device: Config.SerializedEntry<SimpleDevice>

    override val deviceId: String
        get() = device.get()?.id ?: throw IllegalStateException()

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private suspend fun findDevices(): List<SimpleDevice>? {
        return coroutineScope {
            withContext(coroutineContext) {
                logger.debug { "Retrieving device list" }
                try {
                    SpotifyApi.builder()
                        .setAccessToken(authenticator.getToken())
                        .build()
                        .usersAvailableDevices
                        .build()
                        .execute()
                        .map(::SimpleDevice)
                } catch (e: Exception) {
                    logger.error(e) { "Could not retrieve device list" }
                    null
                }
            }
        }
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        device = config.SerializedEntry(
            "device",
            "Spotify device to use",
            DeviceSerializer,
            NonnullConfigChecker,
            ChoiceBox(SimpleDevice::name, { findDevices() }, true)
        )
        return listOf(device)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) {}

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Checking device config")
        if (device.get() == null) {
            throw InitializationException("No device selected")
        }
    }

    override suspend fun close() {
        job.cancel()
    }
}

private data class SimpleDevice(val id: String, val name: String) {
    constructor(device: Device) : this(device.id, device.name)
}

private object DeviceSerializer : ConfigSerializer<SimpleDevice> {
    override fun deserialize(string: String): SimpleDevice {
        return string.split(';').let {
            val id = it[0]
            val name = it.subList(1, it.size).joinToString(";")
            SimpleDevice(id, name)
        }
    }

    override fun serialize(obj: SimpleDevice): String {
        return "${obj.id};${obj.name}"
    }
}

package net.bjoernpetersen.musicbot.sysvolume

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.volume.VolumeHandler
import net.bjoernpetersen.volctl.VolumeControl

class NativeSysVolumeHandler : VolumeHandler {
    override val name: String =
        "Native system master volume control"
    override val description: String =
        "Controls the system's master volume by using native code."

    private lateinit var volumeControl: VolumeControl

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()
    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        withContext(Dispatchers.IO) {
            volumeControl = VolumeControl.newInstanceWithClassLoaderSupport()
        }
    }

    override suspend fun getVolume(): Int {
        return withContext(Dispatchers.IO) { volumeControl.volume }
    }

    override suspend fun setVolume(value: Int) {
        withContext(Dispatchers.IO) { volumeControl.volume = value }
    }

    override suspend fun close() = Unit
}

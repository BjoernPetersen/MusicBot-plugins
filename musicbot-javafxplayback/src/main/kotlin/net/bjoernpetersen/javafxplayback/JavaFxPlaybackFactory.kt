package net.bjoernpetersen.javafxplayback

import java.io.File
import java.io.IOException
import javafx.scene.media.MediaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.UnsupportedAudioFileException
import net.bjoernpetersen.musicbot.spi.plugin.predefined.WavePlaybackFactory

class JavaFxPlaybackFactory : Mp3PlaybackFactory, WavePlaybackFactory {

    override val name: String = "JavaFX"
    override val description: String = "Plays MP3 or Wave songs using JavaFX's included feature"

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()
    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) = Unit

    @Throws(UnsupportedAudioFileException::class, IOException::class)
    override suspend fun createPlayback(inputFile: File): Playback {
        return withContext(Dispatchers.IO) {
            try {
                JavaFxPlayback(inputFile)
            } catch (e: MediaException) {
                if (e.type == MediaException.Type.MEDIA_UNSUPPORTED) {
                    throw UnsupportedAudioFileException(e.message)
                } else {
                    throw IOException(e)
                }
            }
        }
    }

    override suspend fun close() = Unit
}

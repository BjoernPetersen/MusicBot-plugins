package net.bjoernpetersen.musicbot.mpv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.AacPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.AacStreamPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.AviPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.FlacPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.FlacStreamPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.MkvPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3StreamPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp4PlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.VorbisPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.VorbisStreamPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.WavePlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.WaveStreamPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.WmvPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubePlaybackFactory
import net.bjoernpetersen.musicbot.spi.util.FileStorage
import java.io.File
import java.io.IOException
import java.net.URL
import javax.inject.Inject

private const val EXECUTABLE = "mpv"

@Suppress("BlockingMethodInNonBlockingContext")
@IdBase("mpv")
class MpvPlaybackFactory :
    AacPlaybackFactory,
    FlacPlaybackFactory,
    Mp3PlaybackFactory,
    VorbisPlaybackFactory,
    WavePlaybackFactory,
    YouTubePlaybackFactory,
    AviPlaybackFactory,
    MkvPlaybackFactory,
    Mp4PlaybackFactory,
    WmvPlaybackFactory,
    AacStreamPlaybackFactory,
    FlacStreamPlaybackFactory,
    Mp3StreamPlaybackFactory,
    VorbisStreamPlaybackFactory,
    WaveStreamPlaybackFactory,
    CoroutineScope by PluginScope(Dispatchers.IO) {

    override val name: String = "mpv"
    override val description: String = "Plays various files using mpv"

    private val logger = KotlinLogging.logger { }

    private lateinit var config: CliOptions

    @Inject
    private lateinit var fileStorage: FileStorage
    private lateinit var cmdFileDir: File

    override fun createStateEntries(state: Config) = Unit
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        this.config = CliOptions(config)
        return this.config.getShownEntries()
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        progressFeedback.state("Testing executable...")
        withContext(coroutineContext) {
            try {
                ProcessBuilder(EXECUTABLE, "-h", "--no-config").start()
            } catch (e: IOException) {
                progressFeedback.warning("Failed to start mpv.")
                throw InitializationException(e)
            }
        }

        progressFeedback.state("Retrieving plugin dir...")
        cmdFileDir = fileStorage.forPlugin(this, true)
    }

    override suspend fun createPlayback(inputFile: File): Playback {
        return withContext(coroutineContext) {
            if (!inputFile.isFile) throw IOException("File not found: ${inputFile.path}")
            MpvPlayback(
                cmdFileDir,
                inputFile.canonicalPath,
                config
            )
        }
    }

    override suspend fun load(videoId: String): Resource = NoResource
    override suspend fun createPlayback(videoId: String, resource: Resource): Playback {
        logger.debug { "Creating playback for $videoId" }
        return withContext(coroutineContext) {
            MpvPlayback(
                cmdFileDir,
                "ytdl://$videoId",
                config
            )
        }
    }

    override suspend fun createPlayback(streamLocation: URL): Playback {
        logger.debug { "Creating playback for URL $streamLocation" }
        return withContext(coroutineContext) {
            MpvPlayback(
                cmdFileDir,
                streamLocation.toExternalForm(),
                config
            )
        }
    }

    override suspend fun close() {
        run { cancel() }
    }
}

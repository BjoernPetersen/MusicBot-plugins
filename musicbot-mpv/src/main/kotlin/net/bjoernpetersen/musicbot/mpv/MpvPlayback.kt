package net.bjoernpetersen.musicbot.mpv

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.mpv.control.MpvApi
import net.bjoernpetersen.musicbot.mpv.control.MpvBoolean
import net.bjoernpetersen.musicbot.mpv.control.MpvProperty
import net.bjoernpetersen.musicbot.mpv.control.NamedPipe
import net.bjoernpetersen.musicbot.mpv.control.Pipe
import net.bjoernpetersen.musicbot.mpv.control.UnixPipe
import net.bjoernpetersen.musicbot.spi.plugin.AbstractPlayback
import net.bjoernpetersen.musicbot.spi.plugin.PlaybackState
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID

private const val EXECUTABLE = "mpv"

internal class MpvPlayback(
    private val path: String,
    private val options: CliOptions
) : AbstractPlayback() {

    private val logger = KotlinLogging.logger { }

    private val pipePath = createPipePath()
    private val mpv: Process = ProcessBuilder(createCommand()).start()
    private var pipe: Pipe? = null
    private var api: MpvApi? = null

    init {
        launch {
            val exitValue = mpv.waitFor()
            if (exitValue > 0) logger.warn { "mpv exited with non-zero exit value: $exitValue" }
            else logger.debug { "mpv process ended" }
            markDone()
        }

        launch {
            delay(LOAD_TIME_MILLIS)
            pipe = createPipe(pipePath)
            val api = MpvApi(pipe!!)
            this@MpvPlayback.api = api
            api.addPropertyListener(MpvProperty.PAUSE, ::updateState)
            api.addPropertyListener(MpvProperty.PLAYBACK_POSITION, ::updateProgress)
        }
    }

    private fun updateState(isPaused: Any) {
        val state = if (isPaused as Boolean) PlaybackState.PAUSE
        else PlaybackState.PLAY
        feedbackChannel.updateState(state)
    }

    private fun updateProgress(progress: Any) {
        val duration = Duration.ofSeconds((progress as Double).toLong())
            .let { if (it.isNegative) Duration.ZERO else it }
        feedbackChannel.updateProgress(duration)
    }

    private fun createCommand(): List<String> {
        val command = mutableListOf(
            EXECUTABLE,
            "--input-ipc-server=$pipePath",
            "--no-input-terminal",
            "--quiet",
            "--pause"
        )

        options.allOptions.forEach { option ->
            option.getCliArgs().filterNotNull().forEach { command.add(it) }
        }

        command.add(path)
        return command
    }

    private suspend fun getApi(): MpvApi {
        while (api == null) delay(API_ONLINE_WAIT_MILLIS)
        return api!!
    }

    override suspend fun play() {
        withContext(coroutineContext) {
            getApi().setProperty(MpvProperty.PAUSE, MpvBoolean.FALSE)
        }
    }

    override suspend fun pause() {
        withContext(coroutineContext) {
            getApi().setProperty(MpvProperty.PAUSE, MpvBoolean.TRUE)
        }
    }

    override suspend fun close() {
        withContext(coroutineContext) {
            if (mpv.isAlive) {
                try {
                    api?.exit() ?: throw IOException("API not initialized")
                } catch (e: IOException) {
                    logger.warn(e) { "Could not send quit command to mpv" }
                }
            }

            api?.close()
            pipe?.close()

            val exitValue = withTimeoutOrNull(EXIT_TIMEOUT_MILLIS) {
                mpv.waitFor()
            }
            if (exitValue == null) {
                logger.warn { "There is probably an unclosed mpv process." }
                mpv.destroyForcibly()
            }
        }
        super.close()
    }

    private companion object {
        const val API_ONLINE_WAIT_MILLIS: Long = 200
        const val LOAD_TIME_MILLIS: Long = 2000
        const val EXIT_TIMEOUT_MILLIS: Long = 5000
        val isWin = System.getProperty("os.name").toLowerCase().startsWith("win")
        fun createPipePath(): Path {
            val uuid = UUID.randomUUID().toString()
            return if (isWin) {
                Paths.get("""\\.\pipe\$uuid""")
            } else {
                Paths.get("/tmp").resolve(uuid)
            }
        }

        fun createPipe(pipePath: Path): Pipe {
            return if (isWin) NamedPipe(pipePath)
            else UnixPipe(pipePath)
        }
    }
}

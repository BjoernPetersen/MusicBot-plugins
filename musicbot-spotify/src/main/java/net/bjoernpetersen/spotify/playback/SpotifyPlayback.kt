package net.bjoernpetersen.spotify.playback

import com.google.gson.JsonArray
import com.wrapper.spotify.SpotifyApi
import com.wrapper.spotify.exceptions.SpotifyWebApiException
import com.wrapper.spotify.exceptions.detailed.BadGatewayException
import com.wrapper.spotify.exceptions.detailed.NotFoundException
import com.wrapper.spotify.model_objects.miscellaneous.CurrentlyPlayingContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.io.errors.IOException
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.spi.plugin.AbstractPlayback
import net.bjoernpetersen.musicbot.spi.plugin.PlaybackState
import net.bjoernpetersen.spotify.auth.SpotifyAuthenticator
import java.time.Duration

internal class SpotifyPlayback(
    private val authenticator: SpotifyAuthenticator,
    private val deviceId: String,
    private val songId: String
) : AbstractPlayback() {

    private val logger = KotlinLogging.logger {}
    private suspend fun getApi() = SpotifyApi.builder()
        .setAccessToken(authenticator.getToken())
        .build()

    private val songUri = "spotify:track:$songId"
    private var isStarted = false

    override suspend fun pause() {
        withContext(coroutineContext) {
            val error = try {
                getApi().pauseUsersPlayback()
                    .device_id(deviceId)
                    .build()
                    .execute()
                null
            } catch (e: IOException) {
                e
            } catch (e: SpotifyWebApiException) {
                e
            }

            if (error != null) {
                logger.error(error) { "Could not pause playback" }
                feedbackChannel.updateState(PlaybackState.BROKEN)
            }
        }
    }

    private fun launchStateChecker() {
        launch {
            while (!isDone()) {
                delay(Duration.ofSeconds(1))
                checkState()
            }
        }
    }

    override suspend fun play() {
        withContext(coroutineContext) {
            val error = try {
                getApi().startResumeUsersPlayback()
                    .device_id(deviceId)
                    .apply {
                        if (!isStarted) {
                            logger.debug { "Starting song" }
                            uris(JsonArray().apply {
                                add(songUri)
                            })
                            launchStateChecker()
                        }
                    }
                    .build()
                    .execute()
                isStarted = true
                null
            } catch (e: IOException) {
                e
            } catch (e: SpotifyWebApiException) {
                e
            }

            if (error != null) {
                logger.error(error) { "Could not play playback" }
                feedbackChannel.updateState(PlaybackState.BROKEN)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun checkState() {
        val state: CurrentlyPlayingContext? = try {
            getApi().informationAboutUsersCurrentPlayback
                .build()
                .execute()
        } catch (e: SpotifyWebApiException) {
            logger.error(e) { "Could not check state" }
            if (e !is BadGatewayException && e !is NotFoundException) {
                feedbackChannel.updateState(PlaybackState.BROKEN)
            }
            return
        } catch (e: IOException) {
            logger.error(e) { "Could not check state" }
            return
        }

        if (state == null) {
            logger.debug { "Current context is null" }
            return
        }

        state.progress_ms?.let {
            feedbackChannel.updateProgress(Duration.ofMillis(it.toLong()))
        }

        yield()

        state.apply {
            val playbackState = toState() ?: return markDone()
            if (!isDone()) feedbackChannel.updateState(playbackState)
        }
    }

    private fun CurrentlyPlayingContext.toState(): PlaybackState? {
        return if (!is_playing) {
            @Suppress("ComplexCondition")
            if (
                progress_ms == null || progress_ms == 0 ||
                item == null || item.id != songId
            ) null else PlaybackState.PAUSE
        } else if (item != null && item.id != songId) {
            null
        } else {
            PlaybackState.PLAY
        }
    }

    override suspend fun close() {
        // Mark done so the state checker doesn't react to pausing
        markDone()
        // Pause, so the playback actually stops
        pause()
        super.close()
    }
}

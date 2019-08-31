package net.bjoernpetersen.musicbot.youtube.playback

import net.bjoernpetersen.musicbot.api.plugin.Base
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.PlaybackFactory

/**
 * PlaybackFactory for playing YouTube songs/videos.
 */
@Base
interface YouTubePlaybackFactory : PlaybackFactory {

    /**
     * Loads the video with the specified ID.
     *
     * @param videoId a video ID
     * @return a resource which resulted from loading
     */
    suspend fun load(videoId: String): YouTubeResource

    /**
     * Creates a playback object using the specified resource.
     *
     * @param resource the resource which was created by [load]
     * @return a playback object
     */
    suspend fun createPlayback(resource: YouTubeResource): Playback
}

/**
 * A resource for a specific YouTube video.
 */
abstract class YouTubeResource(val videoId: String) : Resource

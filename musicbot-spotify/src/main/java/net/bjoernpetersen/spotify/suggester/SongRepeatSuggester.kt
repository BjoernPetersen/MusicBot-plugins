package net.bjoernpetersen.spotify.suggester

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.SongEntry
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.spotify.provider.SpotifyProvider
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@IdBase("Spotify song repeater")
class SongRepeatSuggester : Suggester, CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    @Inject
    private lateinit var provider: SpotifyProvider
    private lateinit var songUrl: Config.StringEntry
    private lateinit var song: Song

    override val name: String = "Spotify repeater"
    override val description: String = "Plays one song over and over again on repeat." +
        " Recommended song: Kenning West."

    override val subject: String
        get() = song.title

    override suspend fun suggestNext(): Song {
        return song
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> = listOf(song)

    override suspend fun removeSuggestion(song: Song) {}

    override suspend fun notifyPlayed(songEntry: SongEntry) {}

    override suspend fun dislike(song: Song) {}

    @Throws(InitializationException::class)
    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Looking up song")
        try {
            song = songUrl.get()?.let { getSongId(it) }?.let { provider.lookup(it) }
                ?: throw InitializationException("Could not find song")
        } catch (e: NoSuchSongException) {
            throw InitializationException(e)
        }
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        songUrl = config.StringEntry(
            "songUrl",
            "A Spotify song link",
            { if (it?.let(::getSongId) == null) "Invalid URL" else null },
            TextBox, null
        )
        return listOf(songUrl)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override fun createStateEntries(state: Config) {}

    override suspend fun close() {
        job.cancel()
    }
}

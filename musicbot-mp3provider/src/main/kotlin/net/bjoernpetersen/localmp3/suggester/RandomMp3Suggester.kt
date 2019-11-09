package net.bjoernpetersen.localmp3.suggester

import java.util.LinkedList
import javax.inject.Inject
import net.bjoernpetersen.localmp3.provider.Mp3Provider
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter

@IdBase("Random local MP3s")
class RandomMp3Suggester : Suggester {
    private val nextSongs = LinkedList<Song>()
    @Inject
    private lateinit var provider: Mp3Provider

    private lateinit var customSubject: Config.StringEntry

    override val name = "Random local MP3"
    override val description = "Plays random MP3s from a local directory"
    override val subject: String
        get() {
            val subject = if (this::customSubject.isInitialized) customSubject.get()
            else null

            return subject ?: "Random ${provider.subject}"
        }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        customSubject = config.string("DisplayName") {
            description = "Name to display in clients"
            check { null }
            uiNode = TextBox
        }

        return listOf(customSubject)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Loading next songs...")
        refreshNextSongs()
    }

    override suspend fun close() {
        nextSongs.clear()
    }

    private fun refreshNextSongs() {
        provider.getSongs().forEach { nextSongs.add(it) }
        nextSongs.shuffle()
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        if (nextSongs.isEmpty()) refreshNextSongs()
        return nextSongs.subList(
            0,
            minOf(nextSongs.size, minOf(MAX_SUGGESTIONS, maxOf(1, maxLength)))
        )
    }

    override suspend fun suggestNext(): Song {
        getNextSuggestions(1)
        return nextSongs.pop()
    }

    override suspend fun removeSuggestion(song: Song) {
        nextSongs.remove(song)
    }

    private companion object {
        const val MAX_SUGGESTIONS = 20
    }
}

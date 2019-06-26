package net.bjoernpetersen.localmp3.suggester

import net.bjoernpetersen.localmp3.provider.Mp3Provider
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import java.util.LinkedList
import javax.inject.Inject

@IdBase("Random local MP3s")
class Mp3Suggester : Suggester {
    private val nextSongs = LinkedList<Song>()
    @Inject
    private lateinit var provider: Mp3Provider

    private var customSubject: Config.StringEntry? = null

    override val name = "Random local MP3"
    override val description = "Plays random MP3s from a local directory"
    override val subject
        get() = customSubject?.get() ?: "Random ${provider.subject}"

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        customSubject = config.StringEntry(
            "DisplayName",
            "Name to display in clients",
            { null },
            TextBox
        )

        return listOf(customSubject!!)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) {}

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
        return nextSongs.subList(0, minOf(nextSongs.size, minOf(20, maxOf(1, maxLength))))
    }

    override suspend fun suggestNext(): Song {
        getNextSuggestions(1)
        return nextSongs.pop()
    }

    override suspend fun removeSuggestion(song: Song) {
        nextSongs.remove(song)
    }
}

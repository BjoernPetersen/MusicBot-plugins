package net.bjoernpetersen.spotify.suggester

import com.wrapper.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.DeserializationException
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.NumberBox
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.SongEntry
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.BrokenSuggesterException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.ProgressFeedback
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyAuthenticator
import net.bjoernpetersen.musicbot.spi.plugin.predefined.spotify.SpotifyProvider
import net.bjoernpetersen.spotify.marketFromToken
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.min

@Suppress("TooManyFunctions")
@IdBase("Spotify recommendation suggester")
class RecommendationSuggester : Suggester, CoroutineScope by PluginScope() {

    private val logger = KotlinLogging.logger { }

    override val name: String = "Spotify recommendation suggester"
    override val description: String =
        "Suggests Spotify songs based on the last played manually enqueued song"

    @Inject
    private lateinit var auth: SpotifyAuthenticator
    @Inject
    private lateinit var provider: SpotifyProvider

    private lateinit var config: RecommendationConfig

    private lateinit var baseEntry: Config.SerializedEntry<SimpleSong>

    private val baseId: String
        get() = baseEntry.get()?.id
            ?: getSongId(config.fallbackEntry.get()!!)
            ?: throw IllegalStateException("No valid base or fallback set")

    private lateinit var baseSong: Song

    private suspend fun lookupBaseSong(): Song {
        val baseId = baseId
        return provider.lookup(baseId)
    }

    override val subject: String
        get() = "Based on ${baseEntry.get()?.name ?: baseSong.title}"

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        this.config = RecommendationConfig(config)
        return this.config.allEntries
    }

    override fun createStateEntries(state: Config) {
        baseEntry = state.SerializedEntry(
            key = "baseEntry",
            description = "",
            serializer = SimpleSong.Serializer,
            configChecker = { null }
        )
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override suspend fun initialize(progressFeedback: ProgressFeedback) {
        withContext(coroutineContext) {
            progressFeedback.state("Trying to retrieve base song")
            baseSong = try {
                lookupBaseSong()
            } catch (e: NoSuchSongException) {
                throw InitializationException(e)
            } catch (e: IllegalStateException) {
                throw InitializationException(e)
            }

            progressFeedback.state("Filling suggestions")
            fillNextSongs()

            if (nextSongs.isEmpty()) {
                throw InitializationException("Could not get any recommendations")
            }
        }
    }

    private val nextSongs: MutableList<Song> = LinkedList()

    private suspend fun fillNextSongs() {
        withContext(coroutineContext) {
            val api = SpotifyApi.builder()
                .setAccessToken(auth.getToken())
                .build()

            api.recommendations
                .marketFromToken()
                .seed_tracks(baseSong.id)
                .apply {
                    config.apply {
                        targetDanceability.setIfPresent { target_danceability(it) }
                        targetEnergy.setIfPresent { target_energy(it) }

                        minInstrumentalness.setIfPresent { min_instrumentalness(it) }
                        maxInstrumentalness.setIfPresent { max_instrumentalness(it) }

                        minLiveness.setIfPresent { min_liveness(it) }
                        maxLiveness.setIfPresent { max_liveness(it) }
                    }
                }
                .build()
                .execute()
                .tracks
                .map { it.id }
                .let { provider.lookupBatch(it) }
                .forEach { nextSongs.add(it) }
        }
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        return withContext(coroutineContext) {
            if (nextSongs.size <= 1) {
                fillNextSongs()
                if (nextSongs.isEmpty()) {
                    throw BrokenSuggesterException()
                }
            }

            nextSongs.toList().let { it.subList(0, min(maxLength, it.size)) }
        }
    }

    override suspend fun suggestNext(): Song = withContext(coroutineContext) {
        val song = getNextSuggestions(1).first()
        nextSongs.remove(song)
        song
    }

    override suspend fun removeSuggestion(song: Song) = withContext<Unit>(coroutineContext) {
        nextSongs.remove(song)
    }

    override suspend fun notifyPlayed(songEntry: SongEntry) = withContext(coroutineContext) {
        super.notifyPlayed(songEntry)
        if (songEntry.user != null) {
            val song = songEntry.song
            baseEntry.set(SimpleSong(song.id, song.title))
            baseSong = song
            nextSongs.clear()
        }
    }

    override suspend fun close() {
        run { cancel() }
    }
}

private data class SimpleSong(val id: String, val name: String) {
    object Serializer : ConfigSerializer<SimpleSong> {
        override fun serialize(obj: SimpleSong): String {
            return "${obj.id};${obj.name}"
        }

        override fun deserialize(string: String): SimpleSong {
            val splits = string.split(";")
            if (splits.size < 2) throw DeserializationException()
            val id = splits[0]
            val name = splits.subList(1, splits.size).joinToString(";")
            return SimpleSong(id, name)
        }
    }
}

@Suppress("MagicNumber")
private fun Int.toPercentFloat(): Float = toFloat() / 100

private fun Config.SerializedEntry<Int>.setIfPresent(set: (Float) -> Unit) {
    val value = get()!!
    if (value == default) return
    set(value.toPercentFloat())
}

@Suppress("MagicNumber")
private class RecommendationConfig(config: Config) {
    val fallbackEntry by config.string {
        description = "Spotify song URL of a song to base stations on if there is no alternative"
        check { if (it?.let(::getSongId) == null) "Invalid URL" else null }
        uiNode = TextBox
        default("https://open.spotify.com/track/75n8FqbBeBLW2jUzvjdjXV?si=3LjPfzQdTcmnMn05gf7UNQ")
    }

    val targetDanceability by config.serialized<Int> {
        description = "Danceability describes how suitable a track is for dancing based on" +
            " a combination of musical elements including tempo, rhythm stability," +
            " beat strength, and overall regularity."
        serializer = IntSerializer
        check(NonnullConfigChecker)
        uiNode = NumberBox(min = -1)
        default(-1)
    }
    val targetEnergy by config.serialized<Int> {
        description = "Energy represents a perceptual measure of intensity and activity." +
            " Typically, energetic tracks feel fast, loud, and noisy. For example," +
            " death metal has high energy, while a Bach prelude scores low on the scale." +
            " Perceptual features contributing to this attribute include dynamic range," +
            " perceived loudness, timbre, onset rate, and general entropy."
        serializer = IntSerializer
        check(NonnullConfigChecker)
        uiNode = NumberBox(min = -1)
        default(-1)
    }
    val minInstrumentalness by config.serialized<Int> {
        description = "Predicts whether a track contains no vocals. “Ooh” and “aah” sounds are" +
            " treated as instrumental in this context. Rap or spoken word tracks are clearly" +
            " “vocal”. The closer the instrumentalness value is to 100, the greater likelihood" +
            " the track contains no vocal content. Values above 50 are intended to represent" +
            " instrumental tracks, but confidence is higher as the value approaches 100."
        serializer = IntSerializer
        check(NonnullConfigChecker)
        uiNode = NumberBox()
        default(0)
    }
    val maxInstrumentalness by config.serialized<Int> {
        description = "Predicts whether a track contains no vocals. “Ooh” and “aah” sounds are" +
            " treated as instrumental in this context. Rap or spoken word tracks are clearly" +
            " “vocal”. The closer the instrumentalness value is to 100, the greater likelihood" +
            " the track contains no vocal content. Values above 50 are intended to represent" +
            " instrumental tracks, but confidence is higher as the value approaches 100."
        serializer = IntSerializer
        check(NonnullConfigChecker)
        uiNode = NumberBox()
        default(100)
    }
    val minLiveness by config.serialized<Int> {
        description = "Detects the presence of an audience in the recording." +
            " Higher liveness values represent an increased probability that" +
            " the track was performed live. A value above 80 provides strong" +
            " likelihood that the track is live."
        serializer = IntSerializer
        check(NonnullConfigChecker)
        uiNode = NumberBox()
        default(0)
    }
    val maxLiveness by config.serialized<Int> {
        description = "Danceability describes how suitable a track is for dancing based on" +
            " a combination of musical elements including tempo, rhythm stability," +
            " beat strength, and overall regularity."
        serializer = IntSerializer
        check(NonnullConfigChecker)
        uiNode = NumberBox()
        default(100)
    }

    val allEntries: List<Config.Entry<*>>
        get() = listOf(
            fallbackEntry,
            targetDanceability,
            targetEnergy,
            minInstrumentalness, maxInstrumentalness,
            minLiveness, maxLiveness
        )
}

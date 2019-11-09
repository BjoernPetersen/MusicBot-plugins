package net.bjoernpetersen.musicbot.bridge

import javax.inject.Inject
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import net.bjoernpetersen.musicbot.api.auth.BotUser
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.SerializationException
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.choiceBox
import net.bjoernpetersen.musicbot.api.config.serialization
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.PlayerState
import net.bjoernpetersen.musicbot.api.player.QueueEntry
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.ActiveBase
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.api.plugin.NamedPlugin
import net.bjoernpetersen.musicbot.api.plugin.id
import net.bjoernpetersen.musicbot.api.plugin.management.PluginFinder
import net.bjoernpetersen.musicbot.spi.player.Player
import net.bjoernpetersen.musicbot.spi.player.PlayerStateListener
import net.bjoernpetersen.musicbot.spi.player.SongQueue
import net.bjoernpetersen.musicbot.spi.plugin.GenericPlugin
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.PluginLookup
import net.bjoernpetersen.musicbot.spi.plugin.Provider
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter

@IdBase("Bridge")
@ActiveBase
class Bridge : GenericPlugin {
    @Inject
    private lateinit var player: Player
    @Inject
    private lateinit var queue: SongQueue
    @Inject
    private lateinit var pluginFinder: PluginFinder
    @Inject
    private lateinit var pluginLookup: PluginLookup

    override val name: String = "Bridge"
    override val description: String = "Plays a specific song after every song"

    private lateinit var songId: Config.StringEntry
    private lateinit var provider: Config.SerializedEntry<NamedPlugin<Provider>>

    private val song: Song by lazy {
        val providerInfo = provider.get()!!
        val provider = pluginLookup.lookup(providerInfo)!!
        val songId = songId.get()!!
        try {
            runBlocking { provider.lookup(songId) }
        } catch (e: NoSuchSongException) {
            throw InitializationException(e)
        }
    }

    private inner class Listener : PlayerStateListener {
        private val entry: QueueEntry by lazy { QueueEntry(song, BotUser) }
        override fun invoke(oldState: PlayerState, newState: PlayerState) {
            if (oldState.entry != newState.entry && newState.entry != entry) {
                queue.insert(entry)
                queue.move(entry.song, 0)
            }
        }
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        songId = config.string("songId") {
            description = "The song ID to play"
            check(NonnullConfigChecker)
            uiNode = TextBox
        }

        provider = config.serialized("provider") {
            description = "The provider for the song ID"
            check(NonnullConfigChecker)
            serialization {
                serialize { "${it.id};${it.name}" }
                deserialize {
                    val splits = it.split(';')
                    if (splits.size < 2) throw SerializationException()
                    val id = splits[0]
                    val name = splits.subList(1, splits.size).joinToString(";")
                    NamedPlugin(id, name)
                }
            }
            choiceBox {
                describe { it.name }
                lazy()
                refresh {
                    pluginFinder.providers.map {
                        @Suppress("UNCHECKED_CAST")
                        NamedPlugin(it.id.type as KClass<out Provider>, it.name)
                    }
                }
            }
        }

        return listOf(provider, songId)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()
    override fun createStateEntries(state: Config) = Unit

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        val providerInfo = provider.get()
            ?: throw InitializationException("No provider set")
        pluginLookup.lookup(providerInfo)
            ?: throw InitializationException("Provider not found: ${providerInfo.id}")
        songId.get()
            ?: throw InitializationException("songId not set")

        player.addListener(Listener())
    }

    override suspend fun close() = Unit
}

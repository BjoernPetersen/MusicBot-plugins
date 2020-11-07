package net.bjoernpetersen.video.provider

import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.Provider
import java.nio.file.Path

@IdBase("Local videos")
interface VideoProvider : Provider {
    val folder: Config.SerializedEntry<Path>
    fun getSongs(): Collection<Song>
}

package net.bjoernpetersen.localmp3.provider

import java.nio.file.Path
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.Provider

@IdBase("Local MP3s")
interface Mp3Provider : Provider {

    fun getSongs(): Collection<Song>

    /**
     * @return the directory the MP3s are loaded from.
     */
    fun getDirectory(): Path
}

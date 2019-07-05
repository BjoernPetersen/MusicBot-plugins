package net.bjoernpetersen.musicbot.mpv

import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ExperimentalConfigDsl
import net.bjoernpetersen.musicbot.api.config.FileChooser
import net.bjoernpetersen.musicbot.api.config.FileSerializer
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.NumberBox
import net.bjoernpetersen.musicbot.api.config.boolean
import net.bjoernpetersen.musicbot.api.config.serialized
import java.io.File

@UseExperimental(ExperimentalConfigDsl::class)
internal class CliOptions(config: Config) {
    val allOptions: List<CliOption<*, *>>

    init {
        allOptions = ArrayList<CliOption<*, *>>(20).apply {
            cliOption(
                config.boolean("noVideo") {
                    description = "Don't show video for video files"
                    default = true
                },
                { "--video=${if (it) "no" else "auto"}" }
            )
            cliOption(
                config.boolean("fullscreen") {
                    description = "Show videos in fullscreen mode"
                    default = true
                },
                { "--fullscreen=${if (it) "yes" else "no"}" }
            )
            cliOptions(
                config.serialized<Int>("screen") {
                    description = "Screen to show videos on (0-32)"
                    serializer = IntSerializer
                    uiNode = NumberBox(0, 32)
                    check(NonnullConfigChecker)
                    default(1)
                },
                {
                    listOf(
                        "--fs-screen=$it",
                        "--screen=$it"
                    )
                }
            )
            cliOption(
                config.serialized<File>("configFile") {
                    description = "A config file in a custom location to include"
                    serializer = FileSerializer
                    check { if (it != null && !it.isFile) "Not a file" else null }
                    uiNode = FileChooser(false)
                },
                { "--include=${it.absolutePath}" }
            )
            cliOption(
                config.boolean("ignoreSystemConfig") {
                    description = "Ignore the default, system-wide mpv config"
                    default = true
                },
                { "--config=${if (it) "no" else "yes"}" }
            )
            cliOption(
                config.boolean("hideOsc") {
                    description = "Hide the on-screen-controller"
                    default = true
                },
                { if (it) "--no-osc" else null }
            )
            cliOption(
                config.boolean("disableKeyInput") {
                    description = "Disable default key bindings"
                    default = true
                },
                { if (it) "--no-input-default-bindings" else null }
            )
        }
    }

    fun getShownEntries(): List<Config.Entry<*>> = allOptions.map { it.entry }
}

internal fun <T, E : Config.Entry<T>> MutableList<CliOption<*, *>>.cliOptions(
    entry: E,
    getCliArgs: (value: T) -> List<String?>
) {
    add(CliOption(entry, getCliArgs))
}

internal fun <T, E : Config.Entry<T>> MutableList<CliOption<*, *>>.cliOption(
    entry: E,
    getCliArg: (value: T) -> String?
) {
    add(CliOption(entry, { listOf(getCliArg(it)) }))
}

internal class CliOption<T, E : Config.Entry<T>>(
    val entry: E,
    private val getCliArgs: (value: T) -> List<String?>
) {
    fun getCliArgs(): List<String?> {
        return getCliArgs(entry.get()!!)
    }
}

package net.bjoernpetersen.video.suggester

import net.bjoernpetersen.musicbot.api.config.ConfigSerializer
import net.bjoernpetersen.musicbot.api.config.SerializationException
import java.util.Comparator

enum class SortMode(
    val friendlyName: String,
    comparator: Comparator<String>
) : Comparator<String> by comparator {
    NONE("Unsorted", { _, _ -> 0 }),
    ALPHABETICAL("Alphabetical", Comparator.naturalOrder());

    constructor(
        friendlyName: String,
        compareFunction: (String, String) -> Int
    ) : this(friendlyName, Comparator(compareFunction))

    companion object : ConfigSerializer<SortMode> {
        override fun serialize(obj: SortMode): String {
            return obj.name
        }

        override fun deserialize(string: String): SortMode {
            return try {
                valueOf(string)
            } catch (e: IllegalArgumentException) {
                throw SerializationException()
            }
        }
    }
}

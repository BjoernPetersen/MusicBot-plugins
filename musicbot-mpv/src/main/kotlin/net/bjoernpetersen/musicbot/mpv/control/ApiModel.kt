package net.bjoernpetersen.musicbot.mpv.control

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * @param command the command and its arguments
 */
@JsonClass(generateAdapter = true)
data class Command(
    val command: List<Any>,
    @Json(name = "request_id")
    val requestId: Int
)

@JsonClass(generateAdapter = true)
data class CommandResponse(
    val error: String,
    val data: Any?,
    @Json(name = "request_id")
    val requestId: Int
)

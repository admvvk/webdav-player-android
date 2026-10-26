package com.admvvk.webdavplayer.webdav

/**
 * A single entry returned by a WebDAV PROPFIND listing.
 */
data class WebDavResource(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val contentLength: Long,
    val contentType: String?,
)

private val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".m3u8")
private val audioExtensions = listOf(".mp3", ".flac", ".wav", ".aac", ".ogg", ".m4a")

/** Whether this resource is something the player can open (audio or video). */
fun WebDavResource.isPlayable(): Boolean {
    val type = contentType?.lowercase().orEmpty()
    val name = displayName.lowercase()
    return type.startsWith("video/") || type.startsWith("audio/") ||
        videoExtensions.any { name.endsWith(it) } || audioExtensions.any { name.endsWith(it) }
}

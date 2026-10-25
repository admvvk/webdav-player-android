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

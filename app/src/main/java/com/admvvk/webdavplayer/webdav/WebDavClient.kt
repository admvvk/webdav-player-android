package com.admvvk.webdavplayer.webdav

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder

/**
 * Minimal WebDAV client: enough to list a directory (PROPFIND, Depth: 1)
 * and build authenticated URLs for streaming playback.
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {
    private val client = OkHttpClient.Builder().build()

    fun authHeader(): String = Credentials.basic(username, password)

    /** Builds a full URL for a path relative to [baseUrl]. */
    fun resolve(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl.trimEnd('/')
        val rel = path.trimStart('/')
        return "$base/$rel"
    }

    private val propfindBody = """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:propfind xmlns:D="DAV:">
            <D:prop>
                <D:displayname/>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getcontenttype/>
            </D:prop>
        </D:propfind>
    """.trimIndent()

    /**
     * Lists the contents of [path] (relative to [baseUrl]) one level deep.
     * Throws on network/parse errors; caller is expected to catch.
     */
    fun listDirectory(path: String): List<WebDavResource> {
        val url = resolve(path)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("Depth", "1")
            .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WebDavException("PROPFIND failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseMultiStatus(body, url)
        }
    }

    private fun parseMultiStatus(xml: String, requestUrl: String): List<WebDavResource> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val result = mutableListOf<WebDavResource>()

        var href: String? = null
        var displayName: String? = null
        var isCollection = false
        var contentLength = 0L
        var contentType: String? = null
        var inResponse = false
        var inResourceType = false
        var currentTag: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = localName(parser.name)
                    when (name) {
                        "response" -> {
                            inResponse = true
                            href = null; displayName = null; isCollection = false
                            contentLength = 0L; contentType = null
                        }
                        "resourcetype" -> if (inResponse) inResourceType = true
                        // Some non-standard servers (e.g. older cloud.mail.ru deployments)
                        // use other tag names for the "this is a folder" marker, so we
                        // treat any element nested inside <resourcetype> as a directory
                        // signal, not just the literal "collection" tag.
                        else -> {
                            if (inResourceType) isCollection = true
                            currentTag = name
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inResponse) {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "href" -> href = text
                                "displayname" -> displayName = text
                                "getcontentlength" -> contentLength = text.toLongOrNull() ?: 0L
                                "getcontenttype" -> contentType = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = localName(parser.name)
                    if (name == "resourcetype") inResourceType = false
                    if (name == "response" && inResponse) {
                        val h = href
                        if (h != null && !isSameResource(h, requestUrl)) {
                            val decodedName = displayName?.takeIf { it.isNotBlank() }
                                ?: decodeLastSegment(h)
                            // Fallback directory detection: some WebDAV servers omit a
                            // proper <resourcetype><collection/></resourcetype> or use a
                            // custom marker we don't recognize. A trailing slash on the
                            // raw (undecoded) href, or the classic Apache/nginx
                            // "httpd/unix-directory" content-type, is a reliable signal too.
                            val looksLikeDirectory = isCollection ||
                                h.endsWith("/") ||
                                contentType.equals("httpd/unix-directory", ignoreCase = true)
                            result.add(
                                WebDavResource(
                                    href = h,
                                    displayName = decodedName,
                                    isDirectory = looksLikeDirectory,
                                    contentLength = contentLength,
                                    contentType = contentType,
                                )
                            )
                        }
                        inResponse = false
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.displayName.lowercase() }))
    }

    private fun localName(qName: String): String = qName.substringAfter(':').lowercase()

    private fun decodeLastSegment(href: String): String {
        val trimmed = href.trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    /**
     * Compares the *path* of [href] (which may be a bare path like "/Music/"
     * or a full absolute URL depending on the server) against the path of
     * [requestUrl], ignoring scheme/host and a trailing slash. This replaces
     * a previous heuristic that only compared the last path segment, which
     * misidentified the root "/" self-entry (whose last segment is empty)
     * and could leave a spurious top-level item in the listing.
     */
    private fun isSameResource(href: String, requestUrl: String): Boolean {
        fun pathOf(value: String): String {
            val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) {
                value
            } else {
                // Treat a bare path as relative to requestUrl's origin so it can
                // be parsed uniformly.
                val base = requestUrl.toHttpUrl()
                "${base.scheme}://${base.host}${if (value.startsWith("/")) value else "/$value"}"
            }
            val parsed = runCatching { withScheme.toHttpUrl() }.getOrNull() ?: return value.trimEnd('/')
            return runCatching { URLDecoder.decode(parsed.encodedPath, "UTF-8") }
                .getOrDefault(parsed.encodedPath)
                .trimEnd('/')
        }
        return pathOf(href) == pathOf(requestUrl)
    }
}

class WebDavException(message: String) : Exception(message)

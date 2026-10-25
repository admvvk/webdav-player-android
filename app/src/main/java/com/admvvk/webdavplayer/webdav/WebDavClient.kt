package com.admvvk.webdavplayer.webdav

import android.util.Xml
import okhttp3.Credentials
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
                        "collection" -> if (inResponse) isCollection = true
                        else -> currentTag = name
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
                    if (name == "response" && inResponse) {
                        val h = href
                        if (h != null && !isSameResource(h, requestUrl)) {
                            val decodedName = displayName?.takeIf { it.isNotBlank() }
                                ?: decodeLastSegment(h)
                            result.add(
                                WebDavResource(
                                    href = h,
                                    displayName = decodedName,
                                    isDirectory = isCollection,
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

    private fun isSameResource(href: String, requestUrl: String): Boolean {
        val hrefPath = href.trimEnd('/').substringAfterLast('/', missingDelimiterValue = href)
        val reqPath = requestUrl.trimEnd('/').substringAfterLast('/')
        return hrefPath == reqPath && href.trimEnd('/').let { requestUrl.trimEnd('/').endsWith(it) }
    }
}

class WebDavException(message: String) : Exception(message)

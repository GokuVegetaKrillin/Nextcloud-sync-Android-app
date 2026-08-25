package com.example.data.remote

import android.util.Xml
import com.example.data.model.WebDavItem
import com.example.data.model.WebDavQuota
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object WebDavXmlParser {

    private val rfc1123Format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    private val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parsePropfind(xmlContent: String, basePathPrefix: String): List<WebDavItem> {
        return parsePropfind(StringReader(xmlContent), basePathPrefix)
    }

    fun parsePropfind(reader: java.io.Reader, basePathPrefix: String): List<WebDavItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(reader)

        val items = mutableListOf<WebDavItem>()
        var eventType = parser.eventType

        var currentHref = ""
        var currentDisplayName = ""
        var isDirectory = false
        var contentLength: Long = 0
        var ocSize: Long = 0
        var etag = ""
        var lastModified: Long = 0
        var fileId: String? = null
        var permissions: String? = null
        var currentStatus = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "response" -> {
                            currentHref = ""
                            currentDisplayName = ""
                            isDirectory = false
                            contentLength = 0
                            ocSize = 0
                            etag = ""
                            lastModified = 0
                            fileId = null
                            permissions = null
                            currentStatus = ""
                        }
                        "href" -> {
                            currentHref = parser.nextText().trim()
                        }
                        "status" -> {
                            currentStatus = parser.nextText().trim()
                        }
                        "collection" -> {
                            isDirectory = true
                        }
                        "getetag" -> {
                            etag = parser.nextText().trim().removeSurrounding("\"")
                        }
                        "getlastmodified" -> {
                            val text = parser.nextText().trim()
                            lastModified = parseDate(text)
                        }
                        "getcontentlength" -> {
                            contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        "size" -> {
                            // oc:size for folders in Nextcloud
                            ocSize = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        "id" -> {
                            // oc:id
                            fileId = parser.nextText().trim()
                        }
                        "permissions" -> {
                            // oc:permissions
                            permissions = parser.nextText().trim()
                        }
                        "displayname" -> {
                            currentDisplayName = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "response") {
                        if (currentHref.isNotEmpty() && (currentStatus.isEmpty() || currentStatus.contains("200"))) {
                            val decodedHref = try {
                                java.net.URLDecoder.decode(currentHref, "UTF-8")
                            } catch (e: Exception) {
                                currentHref
                            }
                            val cleanPath = normalizePath(decodedHref, basePathPrefix)
                            val finalName = if (currentDisplayName.isNotEmpty()) {
                                currentDisplayName
                            } else {
                                cleanPath.trimEnd('/').substringAfterLast('/')
                            }

                            val finalSize = if (isDirectory) {
                                if (ocSize > 0) ocSize else contentLength
                            } else {
                                contentLength
                            }

                            items.add(
                                WebDavItem(
                                    href = currentHref,
                                    path = cleanPath,
                                    displayName = if (finalName.isEmpty()) "/" else finalName,
                                    isDirectory = isDirectory,
                                    size = finalSize,
                                    etag = etag,
                                    lastModified = if (lastModified > 0) lastModified else System.currentTimeMillis(),
                                    fileId = fileId,
                                    permissions = permissions
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return items
    }

    fun parseQuota(xmlContent: String): WebDavQuota? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xmlContent))

            var used: Long = -1
            var available: Long = -1
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "quota-used-bytes" -> {
                            used = parser.nextText().trim().toLongOrNull() ?: -1
                        }
                        "quota-available-bytes" -> {
                            available = parser.nextText().trim().toLongOrNull() ?: -1
                        }
                    }
                }
                eventType = parser.next()
            }

            if (used >= 0 || available >= 0) {
                val total = if (available > 0 && used >= 0) used + available else 0L
                WebDavQuota(
                    usedBytes = if (used >= 0) used else 0L,
                    availableBytes = if (available >= 0) available else 0L,
                    totalBytes = total
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizePath(href: String, prefix: String): String {
        var path = href
        val prefixIndex = path.indexOf(prefix)
        if (prefixIndex != -1) {
            path = path.substring(prefixIndex + prefix.length)
        }
        if (!path.startsWith("/")) {
            path = "/$path"
        }
        return if (path.length > 1 && path.endsWith("/")) {
            path.dropLast(1)
        } else {
            path
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            rfc1123Format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                iso8601Format.parse(dateStr)?.time ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }
}

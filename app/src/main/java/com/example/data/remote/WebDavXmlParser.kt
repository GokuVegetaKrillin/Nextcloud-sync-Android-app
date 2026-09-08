package com.example.data.remote

import android.util.Log
import android.util.Xml
import com.example.data.model.WebDavItem
import com.example.data.model.WebDavQuota
import org.xmlpull.v1.XmlPullParser
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

    fun parsePropfind(xmlContent: String, basePathPrefix: String, username: String = ""): List<WebDavItem> {
        return parsePropfind(StringReader(xmlContent), basePathPrefix, username)
    }

    fun parsePropfind(reader: java.io.Reader, basePathPrefix: String, username: String = ""): List<WebDavItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        val items = mutableListOf<WebDavItem>()
        var eventType = parser.eventType

        var inResponse = false
        var inPropstat = false
        var inProp = false
        var currentHref = ""
        var currentDisplayName = ""
        var isDirectory = false
        var contentLength: Long = 0
        var ocSize: Long = 0
        var etag = ""
        var lastModified: Long = 0
        var fileId: String? = null
        var permissions: String? = null
        var has200Success = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val rawName = parser.name ?: ""
            val localName = if (rawName.contains(":")) rawName.substringAfter(":") else rawName

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (localName.lowercase()) {
                        "response" -> {
                            inResponse = true
                            currentHref = ""
                            currentDisplayName = ""
                            isDirectory = false
                            contentLength = 0
                            ocSize = 0
                            etag = ""
                            lastModified = 0
                            fileId = null
                            permissions = null
                            has200Success = false
                        }
                        "propstat" -> {
                            inPropstat = true
                        }
                        "prop" -> {
                            inProp = true
                        }
                        "href" -> {
                            if (inResponse && !inPropstat) {
                                currentHref = parser.nextText().trim()
                            }
                        }
                        "status" -> {
                            if (inPropstat) {
                                val statusText = parser.nextText().trim()
                                if (statusText.contains("200")) {
                                    has200Success = true
                                }
                            }
                        }
                        "collection" -> {
                            if (inProp) {
                                isDirectory = true
                            }
                        }
                        "getetag" -> {
                            if (inProp) {
                                val text = parser.nextText().trim().removePrefix("W/").removePrefix("w/").removeSurrounding("\"").trim()
                                if (text.isNotEmpty()) etag = text
                            }
                        }
                        "getlastmodified" -> {
                            if (inProp) {
                                val text = parser.nextText().trim()
                                val parsed = parseDate(text)
                                if (parsed > 0) lastModified = parsed
                            }
                        }
                        "getcontentlength" -> {
                            if (inProp) {
                                val len = parser.nextText().trim().toLongOrNull()
                                if (len != null) contentLength = len
                            }
                        }
                        "size" -> {
                            // oc:size for directories and files
                            if (inProp) {
                                val s = parser.nextText().trim().toLongOrNull()
                                if (s != null && s > 0) ocSize = s
                            }
                        }
                        "id" -> {
                            // oc:id
                            if (inProp) {
                                val id = parser.nextText().trim()
                                if (id.isNotEmpty()) fileId = id
                            }
                        }
                        "permissions" -> {
                            // oc:permissions
                            if (inProp) {
                                val p = parser.nextText().trim()
                                if (p.isNotEmpty()) permissions = p
                            }
                        }
                        "displayname" -> {
                            if (inProp) {
                                val d = parser.nextText().trim()
                                if (d.isNotEmpty()) currentDisplayName = d
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (localName.lowercase()) {
                        "prop" -> inProp = false
                        "propstat" -> inPropstat = false
                        "response" -> {
                            inResponse = false
                            if (currentHref.isNotEmpty()) {
                                val decodedHref = try {
                                    java.net.URLDecoder.decode(currentHref, "UTF-8")
                                } catch (e: Exception) {
                                    currentHref
                                }

                                val cleanPath = normalizePath(decodedHref, basePathPrefix, username)
                                val finalName = if (currentDisplayName.isNotEmpty() && cleanPath != "/" && currentDisplayName != username) {
                                    currentDisplayName
                                } else {
                                    val segment = cleanPath.trimEnd('/').substringAfterLast('/')
                                    if (segment.isEmpty()) "/" else segment
                                }

                                val finalSize = if (isDirectory) {
                                    if (ocSize > 0) ocSize else contentLength
                                } else {
                                    if (contentLength > 0) contentLength else ocSize
                                }

                                items.add(
                                    WebDavItem(
                                        href = currentHref,
                                        path = cleanPath,
                                        displayName = finalName,
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
            }
            eventType = parser.next()
        }

        return items
    }

    fun parseQuota(xmlContent: String): WebDavQuota? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xmlContent))

            var used: Long = -1
            var available: Long = -1
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val rawName = parser.name ?: ""
                    val localName = if (rawName.contains(":")) rawName.substringAfter(":") else rawName
                    when (localName.lowercase()) {
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

    fun normalizePath(href: String, prefix: String, username: String = ""): String {
        var path = href

        // 1. If prefix matches exactly
        if (prefix.isNotEmpty()) {
            val prefixIndex = path.indexOf(prefix)
            if (prefixIndex != -1) {
                path = path.substring(prefixIndex + prefix.length)
            }
        }

        // 2. Fallback: Check for /dav/files/<username>
        if (path.contains("/dav/files/")) {
            val filesIdx = path.indexOf("/dav/files/")
            val afterFiles = path.substring(filesIdx + "/dav/files/".length)
            val nextSlash = afterFiles.indexOf('/')
            path = if (nextSlash != -1) afterFiles.substring(nextSlash) else "/"
        } else if (path.contains("/remote.php/webdav")) {
            val webdavIdx = path.indexOf("/remote.php/webdav")
            path = path.substring(webdavIdx + "/remote.php/webdav".length)
        } else if (username.isNotEmpty() && path.contains("/$username/")) {
            val userIdx = path.indexOf("/$username/")
            path = path.substring(userIdx + "/$username".length)
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

package com.example.data.remote

import com.example.data.model.ServerStatus
import com.example.data.model.WebDavItem
import com.example.data.model.WebDavQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class NextcloudClient {

    private val userAgent = "Mozilla/5.0 (Android) Nextcloud-Desktop-Sync/3.14"

    private val defaultClient: OkHttpClient by lazy {
        createHttpClient(trustAll = false)
    }

    private val trustAllClient: OkHttpClient by lazy {
        createHttpClient(trustAll = true)
    }

    private fun getClient(trustAll: Boolean): OkHttpClient {
        return if (trustAll) trustAllClient else defaultClient
    }

    private fun createHttpClient(trustAll: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (trustAll) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                // Fallback to standard SSL
            }
        }

        return builder.build()
    }

    fun sanitizeServerUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url.trimEnd('/')
    }

    fun getWebDavBaseUrl(serverUrl: String, username: String): String {
        val base = sanitizeServerUrl(serverUrl)
        return "$base/remote.php/dav/files/$username"
    }

    suspend fun checkServerStatus(serverUrl: String, trustAll: Boolean = true): ServerStatus = withContext(Dispatchers.IO) {
        val base = sanitizeServerUrl(serverUrl)
        val statusUrl = "$base/status.php"
        val startTime = System.currentTimeMillis()

        val request = Request.Builder()
            .url(statusUrl)
            .header("User-Agent", userAgent)
            .get()
            .build()

        try {
            val response = getClient(trustAll).newCall(request).execute()
            val duration = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                try {
                    val json = JSONObject(body)
                    ServerStatus(
                        installed = json.optBoolean("installed", true),
                        maintenance = json.optBoolean("maintenance", false),
                        version = json.optString("versionstring", json.optString("version", "Nextcloud Hub")),
                        productname = json.optString("productname", "Nextcloud"),
                        isConnected = true,
                        responseTimeMs = duration,
                        errorMessage = null
                    )
                } catch (e: Exception) {
                    ServerStatus(
                        installed = true,
                        maintenance = false,
                        version = "Nextcloud",
                        productname = "Nextcloud",
                        isConnected = true,
                        responseTimeMs = duration,
                        errorMessage = null
                    )
                }
            } else {
                ServerStatus(
                    isConnected = false,
                    responseTimeMs = duration,
                    errorMessage = "Server returned HTTP ${response.code}: ${response.message}"
                )
            }
        } catch (e: Exception) {
            ServerStatus(
                isConnected = false,
                responseTimeMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: "Connection failed"
            )
        }
    }

    suspend fun listFolder(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        remotePath: String = "/",
        depth: Int = 1,
        trustAll: Boolean = true
    ): Result<List<WebDavItem>> = withContext(Dispatchers.IO) {
        try {
            val davBase = getWebDavBaseUrl(serverUrl, username)
            val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
            val targetUrl = "$davBase$cleanPath"
            val prefix = "/remote.php/dav/files/$username"

            val propfindXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
                  <d:prop>
                    <d:getlastmodified/>
                    <d:getetag/>
                    <d:getcontenttype/>
                    <d:resourcetype/>
                    <d:getcontentlength/>
                    <oc:id/>
                    <oc:permissions/>
                    <oc:size/>
                    <d:quota-used-bytes/>
                    <d:quota-available-bytes/>
                  </d:prop>
                </d:propfind>
            """.trimIndent()

            val credential = Credentials.basic(username, passwordOrToken)
            val request = Request.Builder()
                .url(targetUrl)
                .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
                .header("Authorization", credential)
                .header("User-Agent", userAgent)
                .header("Depth", depth.toString())
                .header("OCS-APIREQUEST", "true")
                .build()

            val response = getClient(trustAll).newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (code == 207 || response.isSuccessful) {
                val items = WebDavXmlParser.parsePropfind(body, prefix)
                Result.success(items)
            } else {
                Result.failure(IOException("PROPFIND failed with HTTP $code: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchQuota(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        trustAll: Boolean = true
    ): WebDavQuota? = withContext(Dispatchers.IO) {
        try {
            val davBase = getWebDavBaseUrl(serverUrl, username)
            val prefix = "/remote.php/dav/files/$username"
            val propfindXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:quota-used-bytes/>
                    <d:quota-available-bytes/>
                  </d:prop>
                </d:propfind>
            """.trimIndent()

            val credential = Credentials.basic(username, passwordOrToken)
            val request = Request.Builder()
                .url(davBase)
                .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
                .header("Authorization", credential)
                .header("User-Agent", userAgent)
                .header("Depth", "0")
                .build()

            val response = getClient(trustAll).newCall(request).execute()
            if (response.isSuccessful || response.code == 207) {
                val body = response.body?.string() ?: ""
                WebDavXmlParser.parseQuota(body)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadFile(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        remotePath: String,
        destFile: File,
        trustAll: Boolean = true,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val davBase = getWebDavBaseUrl(serverUrl, username)
            val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
            val targetUrl = "$davBase$cleanPath"
            val credential = Credentials.basic(username, passwordOrToken)

            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", credential)
                .header("User-Agent", userAgent)
                .get()
                .build()

            val response = getClient(trustAll).newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Download failed HTTP ${response.code}: ${response.message}"))
            }

            val etag = response.header("ETag")?.removeSurrounding("\"") ?: ""
            val lastModifiedHeader = response.header("Last-Modified")
            val totalBytes = response.body?.contentLength() ?: -1L

            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parentFile, "${destFile.name}.tmp_${System.currentTimeMillis()}")

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        onProgress?.invoke(totalRead, totalBytes)
                    }
                    output.flush()
                }
            }

            if (tempFile.exists()) {
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
            }

            Result.success(etag)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadFile(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        remotePath: String,
        sourceFile: File,
        mtimeMs: Long = sourceFile.lastModified(),
        trustAll: Boolean = true,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val davBase = getWebDavBaseUrl(serverUrl, username)
            val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
            val targetUrl = "$davBase$cleanPath"
            val credential = Credentials.basic(username, passwordOrToken)

            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val fileLength = sourceFile.length()
            val mtimeSec = mtimeMs / 1000

            val requestBody = sourceFile.asRequestBody(mediaType)

            val request = Request.Builder()
                .url(targetUrl)
                .put(requestBody)
                .header("Authorization", credential)
                .header("User-Agent", userAgent)
                .header("X-OC-Mtime", mtimeSec.toString())
                .header("OC-Total-Length", fileLength.toString())
                .build()

            val response = getClient(trustAll).newCall(request).execute()
            val code = response.code
            if (code in 200..299) {
                val etag = response.header("ETag")?.removeSurrounding("\"")
                    ?: response.header("OC-ETag")?.removeSurrounding("\"")
                    ?: "etag_${System.currentTimeMillis()}"
                Result.success(etag)
            } else {
                Result.failure(IOException("Upload failed HTTP $code: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createDirectory(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        remotePath: String,
        trustAll: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val davBase = getWebDavBaseUrl(serverUrl, username)
            val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
            val targetUrl = "$davBase$cleanPath"
            val credential = Credentials.basic(username, passwordOrToken)

            val request = Request.Builder()
                .url(targetUrl)
                .method("MKCOL", null)
                .header("Authorization", credential)
                .header("User-Agent", userAgent)
                .build()

            val response = getClient(trustAll).newCall(request).execute()
            if (response.code == 201 || response.code == 405) { // 405 means already exists
                Result.success(true)
            } else {
                Result.failure(IOException("MKCOL failed HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteItem(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        remotePath: String,
        trustAll: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val davBase = getWebDavBaseUrl(serverUrl, username)
            val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
            val targetUrl = "$davBase$cleanPath"
            val credential = Credentials.basic(username, passwordOrToken)

            val request = Request.Builder()
                .url(targetUrl)
                .delete()
                .header("Authorization", credential)
                .header("User-Agent", userAgent)
                .build()

            val response = getClient(trustAll).newCall(request).execute()
            if (response.code in 200..204 || response.code == 404) {
                Result.success(true)
            } else {
                Result.failure(IOException("DELETE failed HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

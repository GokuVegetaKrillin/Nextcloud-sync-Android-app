package com.example.data.remote

import android.content.Context
import com.example.data.model.ServerStatus
import com.example.data.model.WebDavItem
import com.example.data.model.WebDavQuota
import com.example.util.FileTimeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MockNextcloudServer(private val context: Context) {

    private val serverRootDir: File by lazy {
        File(context.filesDir, "mock_nextcloud_remote_storage").apply { mkdirs() }
    }

    init {
        initializeSampleFiles()
    }

    private fun initializeSampleFiles() {
        val seededMarker = File(serverRootDir, ".seeded_v2")
        if (!seededMarker.exists()) {
            val docs = File(serverRootDir, "Documents").apply { mkdirs() }
            val docsWork = File(docs, "Work").apply { mkdirs() }
            val docsWorkReports = File(docsWork, "Reports").apply { mkdirs() }
            val docsWorkPresentations = File(docsWork, "Presentations").apply { mkdirs() }
            val docsPersonal = File(docs, "Personal").apply { mkdirs() }
            val docsPersonalTaxes = File(docsPersonal, "Taxes").apply { mkdirs() }

            val photos = File(serverRootDir, "Photos").apply { mkdirs() }
            val photosVacation = File(photos, "Vacation").apply { mkdirs() }
            val photosVacation2026 = File(photosVacation, "2026").apply { mkdirs() }
            val photosScreenshots = File(photos, "Screenshots").apply { mkdirs() }

            val projects = File(serverRootDir, "Projects").apply { mkdirs() }
            val projectsAndroid = File(projects, "Android").apply { mkdirs() }
            val projectsAndroidSync = File(projectsAndroid, "NextcloudSync").apply { mkdirs() }
            val projectsWeb = File(projects, "WebPortal").apply { mkdirs() }

            val notes = File(serverRootDir, "Notes").apply { mkdirs() }

            File(docs, "Project-Roadmap.md").writeText(
                "# Nextcloud Mobile Sync\n\n- [x] Bi-directional synchronization\n- [x] Background sync\n- [x] Selective sync\n- [x] Custom sync intervals"
            )
            File(docsWorkReports, "Q3_Summary_Report.pdf.txt").writeText(
                "Executive Summary: Nextcloud mobile synchronization operational review."
            )
            File(docsWorkPresentations, "Sync_Architecture_Deck.key.txt").writeText(
                "Presentation slides on CSync reconciliation and TreeView selective sync."
            )
            File(docsPersonalTaxes, "Receipts_2026.csv").writeText(
                "Date,Category,Amount,Description\n2026-08-01,Office,49.99,Nextcloud Subscription\n"
            )
            File(notes, "Meeting_Notes.txt").writeText(
                "Sync interval set to custom schedule. All folders synchronized by default."
            )
            File(projectsAndroidSync, "App_Design_Notes.txt").writeText(
                "Material 3 design system with Nextcloud Blue accents, TreeView widget, and accessible touch targets."
            )
            File(photosVacation2026, "Mountain_Hike.jpg.txt").writeText(
                "[JPEG Image: High-resolution mountain scenic view]"
            )
            File(photos, "Cloud_Diagram.txt").writeText(
                "[SVG/ASCII Architecture Diagram: Android Client <-> Nextcloud WebDAV API]"
            )

            seededMarker.writeText("seeded_v2")
        }
    }

    suspend fun getStatus(serverUrl: String): ServerStatus = withContext(Dispatchers.IO) {
        ServerStatus(
            installed = true,
            maintenance = false,
            version = "Nextcloud Hub 9 (30.0.1)",
            productname = "Nextcloud",
            isConnected = true,
            responseTimeMs = 18,
            errorMessage = null
        )
    }

    suspend fun listFolder(remotePath: String, depth: Int = 1): List<WebDavItem> = withContext(Dispatchers.IO) {
        val cleanPath = remotePath.trim().removePrefix("/")
        val target = if (cleanPath.isEmpty()) serverRootDir else File(serverRootDir, cleanPath)

        if (!target.exists()) return@withContext emptyList()

        val items = mutableListOf<WebDavItem>()

        // Add root/self item
        items.add(fileToWebDavItem(target, if (cleanPath.isEmpty()) "/" else "/$cleanPath"))

        if (depth >= 1 && target.isDirectory) {
            val children = target.listFiles() ?: emptyArray()
            for (child in children) {
                if (child.name.startsWith(".")) continue
                val childRel = if (cleanPath.isEmpty()) "/${child.name}" else "/$cleanPath/${child.name}"
                items.add(fileToWebDavItem(child, childRel))

                if (depth > 1 && child.isDirectory) {
                    // Recursive list
                    items.addAll(listSubdir(child, childRel))
                }
            }
        }

        items
    }

    private fun listSubdir(dir: File, dirRel: String): List<WebDavItem> {
        val items = mutableListOf<WebDavItem>()
        val children = dir.listFiles() ?: return items
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val childRel = "$dirRel/${child.name}"
            items.add(fileToWebDavItem(child, childRel))
            if (child.isDirectory) {
                items.addAll(listSubdir(child, childRel))
            }
        }
        return items
    }

    private fun fileToWebDavItem(file: File, relativePath: String): WebDavItem {
        val isDir = file.isDirectory
        val size = if (isDir) getFolderSize(file) else file.length()
        val etag = "etag_${file.lastModified()}_$size"
        return WebDavItem(
            href = "/remote.php/dav/files/admin$relativePath",
            path = relativePath,
            displayName = if (relativePath == "/") "/" else file.name,
            isDirectory = isDir,
            size = size,
            etag = etag,
            lastModified = file.lastModified(),
            fileId = "oc_${file.name.hashCode()}",
            permissions = if (isDir) "RGDNVCK" else "RGDNVW"
        )
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach {
            size += if (it.isDirectory) getFolderSize(it) else it.length()
        }
        return size
    }

    suspend fun fetchQuota(): WebDavQuota = withContext(Dispatchers.IO) {
        val total = 100L * 1024 * 1024 * 1024 // 100 GB
        val used = getFolderSize(serverRootDir) + (14L * 1024 * 1024 * 1024)
        WebDavQuota(
            usedBytes = used,
            availableBytes = total - used,
            totalBytes = total
        )
    }

    suspend fun downloadFile(remotePath: String, destFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = remotePath.trim().removePrefix("/")
            val source = File(serverRootDir, cleanPath)
            if (!source.exists()) {
                return@withContext Result.failure(Exception("Remote file does not exist: $remotePath"))
            }

            destFile.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            FileTimeHelper.setLastModified(destFile, source.lastModified())
            val etag = "etag_${source.lastModified()}_${source.length()}"
            Result.success(etag)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadFile(remotePath: String, sourceFile: File, mtime: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = remotePath.trim().removePrefix("/")
            val target = File(serverRootDir, cleanPath)
            target.parentFile?.mkdirs()

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            FileTimeHelper.setLastModified(target, mtime)
            val etag = "etag_${target.lastModified()}_${target.length()}"
            Result.success(etag)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createDirectory(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = remotePath.trim().removePrefix("/")
            val target = File(serverRootDir, cleanPath)
            target.mkdirs()
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    var shouldFailDelete: Boolean = false
    var deleteErrorMessage: String? = null

    suspend fun deleteItem(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (shouldFailDelete) {
            val errorMsg = deleteErrorMessage ?: "HTTP 500 Internal Server Error: Nextcloud storage lock error on DELETE '$remotePath'"
            return@withContext Result.failure(java.io.IOException(errorMsg))
        }
        try {
            val cleanPath = remotePath.trim().removePrefix("/")
            val target = File(serverRootDir, cleanPath)
            if (target.exists()) {
                target.deleteRecursively()
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Server simulation controls for testing
    fun addRemoteFile(relativePath: String, content: String): File {
        val target = File(serverRootDir, relativePath.trim().removePrefix("/"))
        target.parentFile?.mkdirs()
        target.writeText(content)
        return target
    }

    fun addRemoteFolder(relativePath: String): File {
        val target = File(serverRootDir, relativePath.trim().removePrefix("/"))
        target.mkdirs()
        return target
    }

    fun deleteRemoteFile(relativePath: String): Boolean {
        val target = File(serverRootDir, relativePath.trim().removePrefix("/"))
        return if (target.exists()) target.deleteRecursively() else false
    }

    fun getServerStorageDir(): File = serverRootDir
}

package com.example.util

import android.os.Build
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant

object FileTimeHelper {
    private const val TAG = "FileTimeHelper"

    /**
     * Updates the last modified timestamp of a local file to match the server timestamp.
     * Uses Java NIO (Files.setLastModifiedTime) on API 26+ and falls back to java.io.File.setLastModified.
     *
     * @param file The local file whose modification time should be updated.
     * @param epochMillis The target modification timestamp in milliseconds since epoch.
     * @return true if the timestamp was updated successfully, false otherwise.
     */
    fun setLastModified(file: File, epochMillis: Long): Boolean {
        if (!file.exists() || epochMillis <= 0L) {
            return false
        }

        var success = false

        // Method 1: Modern NIO API (Recommended for API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val path = Paths.get(file.absolutePath)
                val fileTime = FileTime.from(Instant.ofEpochMilli(epochMillis))
                Files.setLastModifiedTime(path, fileTime)
                success = true
                Log.d(TAG, "Successfully updated mtime via NIO for ${file.name} to $epochMillis")
            } catch (e: Throwable) {
                Log.w(TAG, "NIO setLastModifiedTime failed for ${file.name}: ${e.message}")
            }
        }

        // Method 2: Legacy File API (Fallback)
        if (!success) {
            try {
                success = file.setLastModified(epochMillis)
                Log.d(TAG, "Legacy setLastModified for ${file.name} returned $success")
            } catch (e: Throwable) {
                Log.w(TAG, "Legacy setLastModified failed for ${file.name}: ${e.message}")
            }
        }

        return success
    }
}

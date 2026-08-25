package com.example.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

object StoragePermissionHelper {

    /**
     * Checks if the app has permission to read and write to the specified path or external storage.
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val writePermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val readPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            writePermission && readPermission
        }
    }

    /**
     * Checks if a specific path requires All Files Access / External Storage permissions.
     */
    fun isExternalPath(path: String, context: Context): Boolean {
        if (path.isBlank()) return false
        val internalFilesDir = context.filesDir.absolutePath
        val internalCacheDir = context.cacheDir.absolutePath
        val appSpecificExternal = context.getExternalFilesDir(null)?.absolutePath ?: ""

        // If path is inside app's private sandbox, no external permission is needed
        if (path.startsWith(internalFilesDir) || path.startsWith(internalCacheDir)) {
            return false
        }
        if (appSpecificExternal.isNotEmpty() && path.startsWith(appSpecificExternal)) {
            return false
        }

        // Paths in /storage/emulated/0, /sdcard, etc. require external storage permission
        return true
    }

    /**
     * Attempts to create the directory and verify write access.
     */
    fun canWriteToDirectory(dir: File): Boolean {
        return try {
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) return false
            }
            if (dir.canWrite()) {
                val testFile = File(dir, ".nc_write_test_${System.currentTimeMillis()}")
                if (testFile.createNewFile()) {
                    testFile.delete()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Launches the system settings screen to request All Files Access (Android 11+)
     * or Application Details Settings.
     */
    fun openStoragePermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    openAppSettings(context)
                }
            }
        } else {
            openAppSettings(context)
        }
    }

    /**
     * Opens the standard app info / permissions settings page for this app.
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Ignore fallback failure
        }
    }
}

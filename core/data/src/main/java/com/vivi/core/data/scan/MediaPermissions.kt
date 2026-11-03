package com.vivi.core.data.scan

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

/**
 * 视频可见性的权限矩阵。
 *
 * Android 13 起 READ_MEDIA_VIDEO 就够了；11/12 需要 READ_EXTERNAL_STORAGE；
 * 想看不被媒体库索引的目录才需要 MANAGE_EXTERNAL_STORAGE（应用可以声明但不强制，
 * 因为上架商店对「所有文件访问」有审查）。
 */
object MediaPermissions {

    fun requiredPermissions(context: Context): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= 30 -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun hasLibraryAccess(context: Context): Boolean =
        requiredPermissions(context).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment_isExternalStorageManager()

    private fun Environment_isExternalStorageManager(): Boolean =
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

    /** 「所有文件访问」只能从系统设置页授予，Android 11 起才有这个开关。 */
    fun allFilesSettingsIntent(packageName: String): android.content.Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            android.content.Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        }.getOrNull()
    }

    fun appSettingsIntent(packageName: String): android.content.Intent =
        android.content.Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
}

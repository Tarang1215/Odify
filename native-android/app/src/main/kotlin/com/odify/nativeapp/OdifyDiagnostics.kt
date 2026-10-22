package com.odify.nativeapp

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Pilot observability: distinguishes original offline audio, Odify streaming
 * cache, Google-provider sources, and the actual Android signing identity.
 * Do not count the Google Drive app's internal cache as Odify-owned storage.
 */
data class OdifyPilotStatus(
    val offlineBytes: Long,
    val streamCacheBytes: Long,
    val artworkBytes: Long,
    val networkConnected: Boolean,
    val packageName: String,
    val signingSha1: String
)

object OdifyDiagnostics {
    suspend fun snapshot(context: Context): OdifyPilotStatus =
        withContext(Dispatchers.IO) {
            OdifyPilotStatus(
                offlineBytes = directoryBytes(File(context.filesDir, "odify_audio")),
                streamCacheBytes = directoryBytes(
                    File(context.cacheDir, "odify_media3_stream_cache")
                ),
                artworkBytes = directoryBytes(File(context.filesDir, "drive_art")) +
                    directoryBytes(File(context.filesDir, "odify_covers")),
                networkConnected = hasInternet(context),
                packageName = context.packageName,
                signingSha1 = certificateSha1(context)
            )
        }

    private fun directoryBytes(directory: File): Long {
        if (!directory.exists()) return 0L
        var total = 0L
        val queue = java.util.ArrayDeque<File>()
        queue.add(directory)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 50000) {
            val item = queue.removeFirst()
            if (item.isFile) {
                total += item.length().coerceAtLeast(0)
            } else if (item.isDirectory) {
                item.listFiles()?.forEach { queue.add(it) }
            }
        }
        return total
    }

    private fun hasInternet(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun certificateSha1(context: Context): String = runCatching {
        val manager = context.packageManager
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = manager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            @Suppress("DEPRECATION")
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            ).signatures
        }
        val first = certificates?.firstOrNull()?.toByteArray()
            ?: return@runCatching ""
        MessageDigest.getInstance("SHA-1").digest(first)
            .joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")

    fun humanBytes(bytes: Long): String {
        val mb = bytes.toDouble() / 1048576.0
        return if (mb < 1024.0) "%.1f MB".format(java.util.Locale.US, mb)
            else "%.2f GB".format(java.util.Locale.US, mb / 1024.0)
    }
}

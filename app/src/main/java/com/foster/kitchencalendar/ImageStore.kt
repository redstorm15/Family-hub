package com.foster.kitchencalendar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * A full-screen bitmap is far too big to pass to the launcher directly (Binder's ~1 MB
 * limit), so each render is saved as a PNG and handed over as a content:// URI that
 * only the home-screen launcher is allowed to read.
 */
object ImageStore {
    private const val DIR = "widget_images"

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    fun save(context: Context, widgetId: Int, index: Int, generation: Long, bitmap: Bitmap): Uri {
        val file = File(dir(context), "w${widgetId}_${generation}_$index.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
        grantToLaunchers(context, uri)
        return uri
    }

    private fun grantToLaunchers(context: Context, uri: Uri) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = context.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(home, PackageManager.MATCH_ALL)
        }
        resolved.map { it.activityInfo.packageName }.distinct().forEach { pkg ->
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Delete older renders for this widget, leaving a short grace period for the launcher. */
    fun cleanup(context: Context, widgetId: Int, keepGeneration: Long) {
        val cutoff = System.currentTimeMillis() - 2 * 60_000
        dir(context).listFiles()?.forEach { f ->
            val parts = f.nameWithoutExtension.split("_")
            if (parts.size == 3 && parts[0] == "w$widgetId") {
                val gen = parts[1].toLongOrNull() ?: return@forEach
                if (gen != keepGeneration && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    fun deleteAll(context: Context, widgetId: Int) {
        dir(context).listFiles()?.filter { it.name.startsWith("w${widgetId}_") }?.forEach { it.delete() }
    }
}

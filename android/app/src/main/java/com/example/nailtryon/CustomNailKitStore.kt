package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Publishes a complete kit atomically so a failed replacement preserves the last upload. */
internal class CustomNailKitStore(context: Context) {
    private companion object {
        val lock = Any()
    }
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "custom-nail-kits")
    private val preferences = appContext.getSharedPreferences("custom-nail-kit", Context.MODE_PRIVATE)

    fun load(): File? = synchronized(lock) {
        val name = preferences.getString("latest", null) ?: return null
        if (!Regex("[a-f0-9-]{36}").matches(name)) return null
        val directory = File(root, name)
        val required = listOf("source.png", "reference.png", "manifest.json") +
            NailFinger.entries.map { "${it.name}.png" }
        directory.takeIf { required.all { file -> File(it, file).length() > 0 } }
    }

    fun save(photo: Bitmap, analysis: NailKitAnalysis): File = synchronized(lock) {
        val rowIndex = analysis.selection.rowIndex ?: 0
        val row = analysis.rows.getOrNull(rowIndex)
            ?: error("未能提取完整五片美甲，请换一张图片重试")
        val order = NailKitLayout.fingerOrder(row) ?: row.regions
        val directory = NailKitStore.save(appContext, photo, order, "automatic-row-${rowIndex + 1}", root)
        val previous = preferences.getString("latest", null)
        try {
            if (!preferences.edit().putString("latest", directory.name).commit()) {
                preferences.edit().putString("latest", previous).commit()
                error("无法保存美甲图")
            }
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
        // This app owns these generated UUID directories. Keep only the newly published upload.
        root.listFiles()?.filter { it.isDirectory && it != directory }?.forEach { it.deleteRecursively() }
        directory
    }
}

internal suspend fun prepareCustomNailKit(
    context: Context,
    photo: Bitmap,
    analyze: suspend (Context, Bitmap) -> NailKitAnalysis = ::analyzeNailKit,
): File {
    val analysis = analyze(context, photo)
    return withContext(Dispatchers.IO) { CustomNailKitStore(context).save(photo, analysis) }
}

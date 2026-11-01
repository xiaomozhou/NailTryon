package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable
internal data class TryOnHistoryEntry(
    val id: String,
    val name: String,
    val category: String,
    val createdAt: Long,
    val width: Int,
    val height: Int,
)

/** App-private, lossless originals. noBackupFilesDir keeps these out of reinstall backups. */
internal class TryOnHistoryStore(
    private val directory: File,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun save(style: NailStyle, bitmap: Bitmap): TryOnHistoryEntry {
        require(!bitmap.isRecycled) { "Result bitmap is unavailable" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create history directory")
        val entry = TryOnHistoryEntry(UUID.randomUUID().toString(), style.name, style.category, now(), bitmap.width, bitmap.height)
        val image = imageFile(entry)
        val metadata = File(directory, "${entry.id}.json")
        val imageTemp = File(directory, "${entry.id}.png.tmp")
        val metadataTemp = File(directory, "${entry.id}.json.tmp")
        try {
            imageTemp.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Cannot write history image")
            }
            // The list only reads final metadata, so partially written images never appear.
            if (!imageTemp.renameTo(image)) throw IOException("Cannot commit history image")
            metadataTemp.writeText(json.encodeToString(TryOnHistoryEntry.serializer(), entry), Charsets.UTF_8)
            if (!metadataTemp.renameTo(metadata)) throw IOException("Cannot commit history metadata")
            return entry
        } catch (error: Exception) {
            imageTemp.delete()
            metadataTemp.delete()
            image.delete()
            metadata.delete()
            throw error
        }
    }

    @Synchronized
    fun load(): List<TryOnHistoryEntry> {
        val cutoff = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).minusMonths(3).toInstant().toEpochMilli()
        val records = directory.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
        return records.mapNotNull { metadata ->
            val entry = runCatching { json.decodeFromString(TryOnHistoryEntry.serializer(), metadata.readText(Charsets.UTF_8)) }.getOrNull()
                ?: return@mapNotNull null
            if (!validId(entry.id) || metadata.name != "${entry.id}.json") return@mapNotNull null
            val image = imageFile(entry)
            if (entry.createdAt < cutoff) {
                image.delete()
                metadata.delete()
                return@mapNotNull null
            }
            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, size)
            if (entry.width <= 0 || entry.height <= 0 || size.outWidth != entry.width || size.outHeight != entry.height) null
            else entry
        }.sortedWith(compareByDescending<TryOnHistoryEntry> { it.createdAt }.thenBy { it.id })
    }

    fun imageFile(entry: TryOnHistoryEntry): File {
        require(validId(entry.id)) { "Invalid history identifier" }
        return File(directory, "${entry.id}.png")
    }

    private fun validId(id: String) = id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    companion object {
        fun forContext(context: Context) = TryOnHistoryStore(File(context.noBackupFilesDir, "tryon-history"))
    }
}

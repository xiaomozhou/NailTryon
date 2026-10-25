package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class SeedreamTryOnRequest(
    val handImage: String,
    val nailImage: String,
    val prompt: String,
)

@Serializable
data class SeedreamTryOnResponse(
    val imageDataUrl: String? = null,
    val imageUrl: String? = null,
)

/** Cloud client for the Supabase Edge Function. It never contains the Volcengine API key. */
class SeedreamTryOnClient(
    private val endpoint: String,
    private val demoToken: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(hand: Bitmap, nail: Bitmap, style: NailStyle): Bitmap? = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext null
        val request = SeedreamTryOnRequest(
            handImage = bitmapDataUrl(hand),
            nailImage = bitmapDataUrl(nail),
            prompt = SeedreamPrompt.forStyle(style),
        )
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            demoToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("x-client-token", it) }
        }
        try {
            connection.outputStream.use { it.write(json.encodeToString(SeedreamTryOnRequest.serializer(), request).toByteArray()) }
            if (connection.responseCode !in 200..299) return@withContext null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val result = json.decodeFromString<SeedreamTryOnResponse>(response)
            result.imageDataUrl?.let(::decodeDataUrl) ?: result.imageUrl?.let(::download)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun bitmapDataUrl(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            output.toByteArray()
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap? {
        val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (encoded.isBlank()) return null
        return Base64.decode(encoded, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    private fun download(url: String): Bitmap? = runCatching {
        URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

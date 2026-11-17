package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.roundToInt
import kotlinx.serialization.SerializationException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class SeedreamTryOnRequest(
    val handImage: String,
    val nailImage: String,
    val prompt: String,
    val guideImage: String? = null,
)

@Serializable
data class SeedreamTryOnResponse(
    val imageDataUrl: String? = null,
    val imageUrl: String? = null,
)

@Serializable
internal data class SeedreamTryOnError(
    val error: String? = null,
    val providerCode: String? = null,
    val requestId: String? = null,
)

internal class TryOnCloudException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Cloud client for the Supabase Edge Function. It never contains the Volcengine API key. */
class SeedreamTryOnClient(
    private val endpoint: String,
    private val demoToken: String? = null,
    private val region: String? = null,
    private val anonKey: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(hand: Bitmap, nail: Bitmap, style: NailStyle, guide: Bitmap? = null): Bitmap = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) throw TryOnCloudException("试戴服务地址不可用")
        val request = SeedreamTryOnRequest(
            handImage = bitmapDataUrl(hand, 1280),
            nailImage = bitmapDataUrl(nail, 1280),
            prompt = SeedreamPrompt.forStyle(style) + " 输出宽高比 ${hand.width}:${hand.height}，与图1完全相同的构图。",
            guideImage = guide?.let { bitmapDataUrl(it, 1280) },
        )
        val body = json.encodeToString(SeedreamTryOnRequest.serializer(), request).toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            setFixedLengthStreamingMode(body.size)
            readTimeout = 130_000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            region?.let { setRequestProperty("x-region", it) }
            anonKey?.let {
                setRequestProperty("apikey", it)
                setRequestProperty("Authorization", "Bearer $it")
            }
            demoToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("x-client-token", it) }
        }
        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val diagnostic = try {
                    val buffer = CharArray(4096)
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        var used = 0
                        while (used < buffer.size) {
                            val count = reader.read(buffer, used, buffer.size - used)
                            if (count <= 0) break
                            used += count
                        }
                        String(buffer, 0, used)
                    }
                    body?.let { json.decodeFromString<SeedreamTryOnError>(it) }
                } catch (_: SerializationException) {
                    null
                } catch (_: IOException) {
                    null
                }
                throw cloudResponseError(status, diagnostic, connection.getHeaderField("sb-request-id"))
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val result = json.decodeFromString<SeedreamTryOnResponse>(response)
            result.imageDataUrl?.let(::decodeDataUrl) ?: result.imageUrl?.let(::download)
                ?: throw TryOnCloudException("试戴云服务未返回有效图片")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: TryOnCloudException) {
            throw failure
        } catch (failure: UnknownHostException) {
            throw TryOnCloudException("试戴云服务域名无法解析，请切换手机网络后重试", failure)
        } catch (failure: SSLException) {
            throw TryOnCloudException("试戴云服务安全连接失败，请切换手机网络后重试", failure)
        } catch (timeout: SocketTimeoutException) {
            throw TryOnCloudException("试戴云服务连接或生成超时", timeout)
        } catch (failure: SerializationException) {
            throw TryOnCloudException("试戴云服务返回格式无法读取", failure)
        } catch (failure: IllegalArgumentException) {
            throw TryOnCloudException("试戴云服务返回的图片数据无效", failure)
        } catch (failure: IOException) {
            throw TryOnCloudException("试戴云服务连接失败，请切换手机网络后重试", failure)
        } finally {
            connection.disconnect()
        }
    }

    private fun bitmapDataUrl(bitmap: Bitmap, maxSide: Int): String {
        val scale = (maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ) else bitmap
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!resized.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                    throw TryOnCloudException("试戴照片编码失败，请重新选择")
                }
                output.toByteArray()
            }
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap? {
        val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (encoded.isBlank()) return null
        return Base64.decode(encoded, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    private fun download(url: String): Bitmap? {
        val imageUrl = URL(url)
        if (imageUrl.protocol != "https") return null
        val connection = imageUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun cloudResponseError(status: Int, error: SeedreamTryOnError?, gatewayRequestId: String?): TryOnCloudException {
    val message = when (error?.error) {
        "seedream_not_configured", "seedream_authorization_failed" -> "试戴云服务的模型或密钥配置异常"
        "seedream_content_rejected" -> "试戴照片未通过云端审核，请换一张清晰的手部照片"
        "seedream_invalid_input", "invalid_images", "payload_too_large" -> "试戴云服务未接受图片，请重新选择照片"
        "seedream_rate_limited" -> "试戴云服务请求过多，请稍后重试"
        "seedream_timeout" -> "试戴云端生成超时，请重试"
        "seedream_empty_result" -> "试戴云服务未返回有效图片，请重试"
        "seedream_unavailable" -> "试戴云服务暂时无法连接生图模型，请稍后重试"
        "seedream_request_failed" -> "试戴生图模型返回错误，请稍后重试"
        else -> "试戴云服务返回错误（HTTP $status）"
    }
    val safe = Regex("[A-Za-z0-9_.-]{1,128}")
    val details = listOfNotNull(
        error?.providerCode?.takeIf { it != "unknown" && safe.matches(it) },
        (error?.requestId ?: gatewayRequestId)?.takeIf(safe::matches)?.let { "请求编号 $it" },
    )
    return TryOnCloudException(listOf(message, *details.toTypedArray()).joinToString("\n"))
}

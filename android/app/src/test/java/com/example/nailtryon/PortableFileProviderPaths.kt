package com.example.nailtryon

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.lang.reflect.Proxy
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

/**
 * Test-host adapter: AndroidX's private PathStrategy assumes '/' even when Robolectric's
 * java.io.File runs on Windows. Use host-aware canonical paths with the REAL XML roots.
 * Manifest parsing, provider export/grants, openFile, delete, and JPEG I/O remain real.
 * This adapter is test-only and is unnecessary on Android or Unix test hosts.
 */
internal object PortableFileProviderPaths {
    fun install(context: Context) {
        if (File.separatorChar != '\\') return
        val authority = "${context.packageName}.camera"
        val provider = ShadowContentResolver.getProvider(Uri.parse("content://$authority/camera/prepare.jpg"))!!
        runCatching { FileProvider.getUriForFile(context, authority, File(context.cacheDir, "camera/prepare.jpg")) }
        val cache = ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache")
        val strategy = cache.getValue(authority)
        val roots = ReflectionHelpers.getField<Map<String, File>>(strategy, "mRoots")
        check(roots.keys == setOf("camera"))
        check(roots.getValue("camera").canonicalFile == File(context.cacheDir, "camera").canonicalFile)
        val strategyType = Class.forName("androidx.core.content.FileProvider\$PathStrategy")
        val portable = Proxy.newProxyInstance(strategyType.classLoader, arrayOf(strategyType)) { _, method, args ->
            when (method.name) {
                "getUriForFile" -> {
                    val file = (args!![0] as File).canonicalFile
                    val entry = roots.entries.filter {
                        file.toPath().startsWith(it.value.canonicalFile.toPath()) && file != it.value.canonicalFile
                    }.maxByOrNull { it.value.canonicalPath.length }
                        ?: throw IllegalArgumentException("File is outside configured roots")
                    val relative = entry.value.canonicalFile.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    Uri.Builder().scheme("content").authority(authority).encodedPath("/${Uri.encode(entry.key)}/${Uri.encode(relative, "/")}").build()
                }
                "getFileForUri" -> {
                    val segments = (args!![0] as Uri).pathSegments
                    val root = roots[segments.firstOrNull()]?.canonicalFile
                        ?: throw IllegalArgumentException("Unknown root")
                    val file = File(root, segments.drop(1).joinToString(File.separator)).canonicalFile
                    if (file == root || !file.toPath().startsWith(root.toPath())) throw SecurityException("Path escapes configured root")
                    file
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        cache[authority] = portable
        ReflectionHelpers.setField(provider, "mLocalPathStrategy", portable)
    }
}

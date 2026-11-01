package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TryOnHistoryStoreTest {
    private lateinit var root: File
    private var clock = Instant.parse("2026-11-02T08:00:00Z").toEpochMilli()
    private val style = NailCatalog.styles.first()
    private fun store() = TryOnHistoryStore(root) { clock }

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "history-test-${UUID.randomUUID()}")
    }

    private fun photo(width: Int = 241, height: Int = 319) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
            setPixel(0, 0, Color.RED)
            setPixel(width - 1, 0, Color.GREEN)
            setPixel(0, height - 1, Color.BLUE)
            setPixel(width - 1, height - 1, Color.YELLOW)
        }

    @Test fun losslessHistoryRetainsEveryPixelAndOriginalDimensions() {
        val image = photo()
        val storage = store()
        val entry = storage.save(style, image)
        assertEquals(241, entry.width)
        assertEquals(319, entry.height)
        val decoded = BitmapFactory.decodeFile(storage.imageFile(entry).absolutePath)!!
        val expected = IntArray(image.width * image.height)
        val actual = IntArray(decoded.width * decoded.height)
        image.getPixels(expected, 0, image.width, 0, 0, image.width, image.height)
        decoded.getPixels(actual, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        assertArrayEquals(expected, actual)
        assertEquals(listOf(entry), storage.load())
    }

    @Test fun landscapeDimensionsAlsoSurviveStoreReconstruction() {
        val entry = store().save(style, photo(400, 160))
        val reopened = store()
        assertEquals(listOf(entry), reopened.load())
        val image = BitmapFactory.decodeFile(reopened.imageFile(entry).absolutePath)!!
        assertEquals(400, image.width)
        assertEquals(160, image.height)
    }

    @Test fun multipleResultsForTheSameStyleRemainDistinctAndNewestFirst() {
        val storage = store()
        val older = storage.save(style, photo())
        clock += 1000
        val newer = storage.save(style, photo(400, 160))
        assertNotEquals(older.id, newer.id)
        assertEquals(listOf(newer, older), storage.load())
    }

    @Test fun expiredRecordsAreRemovedAtThreeMonthsWithoutDeletingRecentOrSourcePhotos() {
        val storage = store()
        clock = Instant.parse("2026-07-31T08:00:00Z").toEpochMilli()
        val expired = storage.save(style, photo())
        clock = Instant.parse("2026-08-03T08:00:00Z").toEpochMilli()
        val recent = storage.save(style, photo())
        val source = File(root.parentFile, "original-hand.jpg").apply { writeText("keep original") }
        clock = Instant.parse("2026-11-02T08:00:00Z").toEpochMilli()
        assertEquals(listOf(recent), storage.load())
        assertFalse(storage.imageFile(expired).exists())
        assertFalse(File(root, "${expired.id}.json").exists())
        assertTrue(storage.imageFile(recent).exists())
        assertEquals("keep original", source.readText())
    }

    @Test fun missingOrCorruptImagesAndMetadataDoNotBreakOtherHistoryRecords() {
        val storage = store()
        val good = storage.save(style, photo())
        val missing = storage.save(style, photo())
        storage.imageFile(missing).delete()
        val broken = storage.save(style, photo())
        storage.imageFile(broken).writeText("not an image")
        File(root, "broken.json").writeText("not json")
        File(root, "incomplete.png.tmp").writeText("partial write")
        assertEquals(listOf(good), storage.load())
    }

    @Test fun corruptedIdentifiersCannotReadOrDeleteFilesOutsideHistoryDirectory() {
        val storage = store()
        root.mkdirs()
        val outside = File(root.parentFile, "outside.png").apply { writeText("private") }
        val bad = TryOnHistoryEntry("../outside", "损坏记录", "纯色美甲", 0, 10, 10)
        File(root, "bad.json").writeText(Json.encodeToString(TryOnHistoryEntry.serializer(), bad))
        assertTrue(storage.load().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { storage.imageFile(bad) }
        assertEquals("private", outside.readText())
    }

    @Test fun failedWritesDoNotCreateFakeHistoryEntries() {
        root.parentFile.mkdirs()
        root.writeText("directory unavailable")
        assertThrows(IOException::class.java) { store().save(style, photo()) }
        assertTrue(store().load().isEmpty())
        assertEquals("directory unavailable", root.readText())
    }

    @Test fun appHistoryUsesNonBackupPrivateStorageAndRejectsUnavailableBitmaps() {
        val context = RuntimeEnvironment.getApplication()
        val storage = TryOnHistoryStore.forContext(context)
        val entry = storage.save(style, photo())
        assertTrue(storage.imageFile(entry).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertFalse(storage.imageFile(entry).canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        assertThrows(IllegalArgumentException::class.java) { storage.save(style, photo().apply { recycle() }) }
        assertEquals(listOf(entry), storage.load())
    }
}

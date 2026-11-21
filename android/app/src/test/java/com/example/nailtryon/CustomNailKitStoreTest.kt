package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomNailKitStoreTest {
    @Test fun tenPieceBoardUsesSelectedWholeRowAndRetainsExactlyFiveFingerAssets() {
        val context = RuntimeEnvironment.getApplication()
        val source = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val rows = listOf(KitTestFixture.row(), KitTestFixture.row(500, reverse = true))
        val store = CustomNailKitStore(context)
        val directory = store.save(source, NailKitAnalysis(rows, KitSelection(1, "left", false)))
        val pieces = NailKitStore.loadPieces(context, NailStyle("custom", "我的美甲", "自选美甲", ""), directory.path)
        try {
            assertEquals(NailFinger.entries.toSet(), pieces.keys)
            assertTrue(pieces.getValue(NailFinger.THUMB).width > pieces.getValue(NailFinger.LITTLE).width)
            assertTrue(File(directory, "manifest.json").readText().contains("automatic-row-2"))
            assertEquals(directory, CustomNailKitStore(context).load())
        } finally { pieces.values.forEach { it.recycle() }; source.recycle() }
    }

    @Test fun fivePieceBoardIsSelectedAutomaticallyEvenWithoutOcrDecisionAndReplacementSurvivesCacheClear() {
        val context = RuntimeEnvironment.getApplication()
        val source = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        val store = CustomNailKitStore(context)
        try {
            val analysis = NailKitAnalysis(listOf(KitTestFixture.row()), KitSelection(null, "ocr failed", true))
            val first = store.save(source, analysis)
            val second = store.save(source, analysis)
            assertNotEquals(first, second)
            assertFalse(first.exists())
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            assertEquals(second, CustomNailKitStore(context).load())
            assertThrows(IllegalStateException::class.java) {
                store.save(source, NailKitAnalysis(emptyList(), KitSelection(null, "no row", true)))
            }
            assertEquals(second, store.load())
        } finally { source.recycle() }
    }
}

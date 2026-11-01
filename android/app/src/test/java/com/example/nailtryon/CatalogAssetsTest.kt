package com.example.nailtryon

import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogAssetsTest {
    @Test fun allTwentyOriginalStylesAreInTheirExpectedCategories() {
        val expected = mapOf(
            "纯色美甲" to setOf("车厘子红", "蓝莓黑", "柠檬黄", "皮皮粉"),
            "幻彩美甲" to setOf("春野霓虹", "轻雾极光", "粉雾樱瓣", "落日奶橘"),
            "手绘美甲" to setOf("奶芙小狗", "星闪黑猫", "复古酒叙", "青雾花露"),
            "主题美甲" to setOf("辣妹星豹", "青星奶闪", "蜜色星爪", "软萌星喵"),
            "立体美甲" to setOf("粉锻芭蕾", "格纹千金", "蜜橘珠链", "浅桃锻带"),
        )
        assertEquals(expected.keys.toList(), NailCatalog.categories)
        assertEquals(20, NailCatalog.styles.size)
        expected.forEach { (category, names) ->
            assertEquals(names, NailCatalog.byCategory(category).map { it.name }.toSet())
            assertEquals(4, NailCatalog.byCategory(category).size)
        }
        assertEquals(20, NailCatalog.styles.map { it.id }.toSet().size)
        assertEquals(20, NailCatalog.styles.map { it.drawableName }.toSet().size)
    }

    @Test fun everyStyleHasADecodableBundledImage() {
        val context = RuntimeEnvironment.getApplication()
        NailCatalog.styles.forEach { style ->
            val resource = context.resources.getIdentifier(style.drawableName, "drawable", context.packageName)
            assertNotEquals("Missing image for ${style.name}", 0, resource)
            val bitmap = BitmapFactory.decodeResource(context.resources, resource)
            assertNotNull("Cannot decode ${style.name}", bitmap)
            assertTrue("Empty image for ${style.name}", bitmap.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
    }
}

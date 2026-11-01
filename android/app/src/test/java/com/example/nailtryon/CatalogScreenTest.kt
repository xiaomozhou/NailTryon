package com.example.nailtryon

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun switchingEachCategoryDisplaysAllFourCardsIncludingAddedStyles() {
        NailCatalog.categories.forEach { category ->
            compose.onNodeWithText(category.removeSuffix("美甲")).performScrollTo().performClick()
            NailCatalog.byCategory(category).forEach { style ->
                compose.onNodeWithTag("nail-catalog").performScrollToNode(hasText(style.name))
                compose.onNodeWithText(style.name).assertIsDisplayed()
            }
        }
    }
}

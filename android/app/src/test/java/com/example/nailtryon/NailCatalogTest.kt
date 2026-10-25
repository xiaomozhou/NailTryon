package com.example.nailtryon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NailCatalogTest {
    @Test fun hasFiveCategories() { assertEquals(5, NailCatalog.categories.size) }
    @Test fun everyCategoryHasStyles() { NailCatalog.categories.forEach { assertTrue(NailCatalog.byCategory(it).isNotEmpty()) } }
    @Test fun styleNamesAreShort() { assertTrue(NailCatalog.styles.all { it.name.length <= 8 }) }
}

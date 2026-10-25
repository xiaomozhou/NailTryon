package com.example.nailtryon

import org.junit.Assert.assertTrue
import org.junit.Test

class SeedreamPromptTest {
    @Test
    fun usesPinnedSeedream45Model() {
        assertTrue(SeedreamPrompt.defaultModel == "doubao-seedream-4-5-251128")
    }

    @Test
    fun promptPreservesHandAndLimitsEditsToNails() {
        val prompt = SeedreamPrompt.forStyle(NailCatalog.styles.first())
        assertTrue(prompt.contains("只修改指甲区域"))
        assertTrue(prompt.contains("不改变手指数量"))
    }

    @Test
    fun promptContainsSelectedStyleAndCategory() {
        val style = NailCatalog.styles.last()
        val prompt = SeedreamPrompt.forStyle(style)
        assertTrue(prompt.contains(style.name))
        assertTrue(prompt.contains(style.category))
    }
}

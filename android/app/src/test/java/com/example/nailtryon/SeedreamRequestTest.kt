package com.example.nailtryon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedreamRequestTest {
    @Test
    fun requestSerializesBothImagesAndPrompt() {
        val request = SeedreamTryOnRequest("data:image/jpeg;base64,hand", "data:image/jpeg;base64,nail", "prompt")
        val encoded = Json.encodeToString(SeedreamTryOnRequest.serializer(), request)
        assertTrue(encoded.contains("handImage"))
        assertTrue(encoded.contains("nailImage"))
        assertEquals("prompt", Json.parseToJsonElement(encoded).jsonObject["prompt"]?.toString()?.trim('"'))
    }
}

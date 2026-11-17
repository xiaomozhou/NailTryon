package com.example.nailtryon

import org.junit.Assert.*
import org.junit.Test

class SeedreamCloudErrorTest {
    @Test fun modelErrorCodesAreExplainedAndTraceable() {
        val error = cloudResponseError(
            502,
            SeedreamTryOnError("seedream_content_rejected", "InputImageSensitiveContentDetected", "trace-123"),
            "gateway-456",
        )
        assertTrue(error.message!!.contains("未通过云端审核"))
        assertTrue(error.message!!.contains("InputImageSensitiveContentDetected"))
        assertTrue(error.message!!.contains("请求编号 trace-123"))
    }

    @Test fun platformErrorWithoutJsonUsesGatewayRequestId() {
        val error = cloudResponseError(502, null, "gateway-456")
        assertEquals("试戴云服务返回错误（HTTP 502）\n请求编号 gateway-456", error.message)
        assertFalse(cloudResponseError(502, SeedreamTryOnError(providerCode = "<html>"), null).message!!.contains("<html>"))
    }
}

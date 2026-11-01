package com.example.nailtryon

/** Prompt contract shared by the future cloud provider and local fallback. */
object SeedreamPrompt {
    const val defaultModel = "doubao-seedream-5-0-pro-260628"

    fun forStyle(style: NailStyle): String =
        "在保持手部姿态、肤色、背景、光线和构图不变的前提下，" +
            "将参考图中的${style.name}（${style.category}）美甲自然贴合到手部照片的指甲上。" +
            "只修改指甲区域，不改变手指数量、手指形状和人物身份；保持甲面边缘清晰、" +
            "颜色与装饰细节准确，加入符合原照片光线的自然高光和轻微阴影，输出真实的美甲试戴效果。"
}

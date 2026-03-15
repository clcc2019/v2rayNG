package com.v2ray.ang.domain.main

object ServerDelayResultParser {
    private val delayPatterns = listOf(
        Regex("(\\d+)\\s*ms", RegexOption.IGNORE_CASE),
        Regex("(\\d+)\\s*毫秒")
    )

    fun parseDelayMillis(content: String?): Long? {
        val raw = content.orEmpty()
        return delayPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
        }
    }

    fun parseDelayResult(content: String?): Long? {
        return parseDelayMillis(content) ?: when {
            content.orEmpty().contains("fail", ignoreCase = true) -> -1L
            content.orEmpty().contains("error", ignoreCase = true) -> -1L
            content.orEmpty().contains("失败") -> -1L
            content.orEmpty().contains("无互联网") -> -1L
            else -> null
        }
    }
}

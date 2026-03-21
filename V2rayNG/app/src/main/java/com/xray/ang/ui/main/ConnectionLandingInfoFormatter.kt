package com.xray.ang.ui

import com.xray.ang.util.Utils

internal object ConnectionLandingInfoFormatter {
    private const val IPV6_HEAD_SEGMENTS = 2
    private const val IPV6_TAIL_SEGMENTS = 2
    private const val IPV6_FALLBACK_HEAD_CHARS = 8
    private const val IPV6_FALLBACK_TAIL_CHARS = 6

    fun extract(content: String): String? {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.size <= 1) {
            return null
        }
        val landingInfo = lines.drop(1).joinToString(" ").trim()
        return formatForDock(landingInfo)
    }

    private fun formatForDock(raw: String): String? {
        if (raw.isBlank()) {
            return null
        }
        val firstSegment = raw.substringBefore("·").trim()
        if (firstSegment.isBlank()) {
            return null
        }
        if (firstSegment.startsWith("(") && firstSegment.contains(')')) {
            val location = firstSegment.substringAfter('(').substringBefore(')').trim()
            val ip = compactIp(firstSegment.substringAfter(')', "").trim())
            if (ip.isEmpty()) {
                return null
            }
            return if (location.isBlank() || location.equals("unknown", ignoreCase = true)) {
                ip
            } else {
                "$ip · $location"
            }
        }
        return compactIp(firstSegment)
    }

    private fun compactIp(value: String): String {
        val ip = value.trim()
        if (ip.isBlank()) {
            return ip
        }
        if (!Utils.isPureIpAddress(ip) || !ip.contains(':')) {
            return ip
        }
        val wrapped = ip.startsWith("[") && ip.endsWith("]")
        val rawIp = ip.removePrefix("[").removeSuffix("]")
        val visibleSegments = rawIp.split(':').filter { it.isNotEmpty() }
        val shortened = if (visibleSegments.size > IPV6_HEAD_SEGMENTS + IPV6_TAIL_SEGMENTS) {
            buildString {
                append(visibleSegments.take(IPV6_HEAD_SEGMENTS).joinToString(":"))
                append(":")
                append("…")
                append(":")
                append(visibleSegments.takeLast(IPV6_TAIL_SEGMENTS).joinToString(":"))
            }
        } else if (rawIp.length > IPV6_FALLBACK_HEAD_CHARS + IPV6_FALLBACK_TAIL_CHARS + 1) {
            "${rawIp.take(IPV6_FALLBACK_HEAD_CHARS)}…${rawIp.takeLast(IPV6_FALLBACK_TAIL_CHARS)}"
        } else {
            rawIp
        }
        return if (wrapped) "[$shortened]" else shortened
    }
}

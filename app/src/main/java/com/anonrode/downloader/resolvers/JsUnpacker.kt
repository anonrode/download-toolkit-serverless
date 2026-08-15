package com.anonrode.downloader.resolvers

import java.util.regex.Pattern

/**
 * Pure Kotlin deobfuscator for Dean Edwards' p.a.c.k.e.r payloads:
 * eval(function(p,a,c,k,e,d){...}('payload',radix,count,'a|b|c'.split('|')))
 * Ported 1:1 from src/resolvers.py _unpack_packed_js.
 */
object JsUnpacker {

    private val PACKER_PATTERN = Pattern.compile(
        "\\}\\s*\\(\\s*'(.*?)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'(.*?)'\\.split\\('\\|'\\)",
        Pattern.DOTALL
    )

    fun unpack(packedJs: String): String {
        try {
            val matcher = PACKER_PATTERN.matcher(packedJs)
            if (!matcher.find()) return ""

            var payload = matcher.group(1) ?: return ""
            val radix = matcher.group(2)?.toIntOrNull() ?: return ""
            val count = matcher.group(3)?.toIntOrNull() ?: return ""
            val words = matcher.group(4)?.split("|") ?: return ""

            payload = payload.replace("\\'", "'").replace("\\\\", "\\")

            val table = HashMap<String, String>()
            for (i in 0 until count) {
                val key = baseN(i, radix)
                val word = if (i < words.size && words[i].isNotEmpty()) words[i] else key
                table[key] = word
            }

            val tokenPattern = Pattern.compile("\\b\\w+\\b")
            val tokenMatcher = tokenPattern.matcher(payload)
            val sb = StringBuffer()

            while (tokenMatcher.find()) {
                val token = tokenMatcher.group()
                val replacement = table[token] ?: token
                tokenMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
            }
            tokenMatcher.appendTail(sb)
            return sb.toString()
        } catch (_: Exception) {
            return ""
        }
    }

    private fun baseN(num: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(digits[n % base])
            n /= base
        }
        return sb.reverse().toString()
    }
}

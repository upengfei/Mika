package com.wangzan101.mika.core.automation

import java.text.Normalizer

/** Literal comparison, allowing display whitespace/full-width variants but no semantic rewriting. */
object TestLiteralEvidence {
    fun contains(expected: String, reads: List<String>): Boolean {
        val wanted = normalize(expected)
        if (wanted.isBlank()) return false
        return reads.any { read ->
            val text = normalize(read)
            var at = text.indexOf(wanted)
            while (at >= 0) {
                val end = at + wanted.length
                val left = !wanted.first().isDigit() || at == 0 || text[at - 1] !in NUMERIC
                val right = !wanted.last().isDigit() || end == text.length || text[end] !in NUMERIC
                if (left && right) return@any true
                at = text.indexOf(wanted, at + 1)
            }
            false
        }
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "").replace(Regex("\\s+"), " ").trim()
    private const val NUMERIC = "0123456789.,+-"
}

package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.knowledge.*

/** Bounded personal recommendations; full lookup tools and built-in instructions remain available. */
object MpGuideIndex {
    const val MAX_PERSONAL = 3
    const val PERSONAL_BUDGET = 1200
    private fun line(s: GuideInfo, compact: Boolean = false): String =
        "- ${s.kind}:${s.name}(${if (compact) s.display.take(80) else s.display}):${if (compact) s.description.take(180).replace('\n', ' ') else s.description}"

    fun index(catalog: GuideCatalog, task: String, installed: List<Pair<String, String>> = emptyList()): String {
        val knowledge = catalog.find(task, limit = 3, minScore = RECOMMEND_MIN_SCORE, among = KNOWLEDGE_KINDS)
        val builtIn = catalog.items.filter { it.kind == KnowledgeIndex.SKILL }.sortedBy { it.name }
        val matched = catalog.find(task, limit = Int.MAX_VALUE, among = setOf(PERSONAL_KIND))
        val appNames = knowledge.map { it.name }.toSet() + installed.filter { (pkg, name) ->
            task.contains(pkg, ignoreCase = true) || (name.length >= 2 && task.contains(name, ignoreCase = true))
        }.map { it.first }
        val byApp = catalog.items.filter { it.kind == PERSONAL_KIND && it.apps.any(appNames::contains) }.sortedBy { it.name }
        val candidates = (matched + byApp).distinctBy { it.name }
        var budget = PERSONAL_BUDGET
        val personal = candidates.mapNotNull { info ->
            val row = line(info, compact = true)
            if (row.length + 1 > budget) null else row.also { budget -= row.length + 1 }
        }.take(MAX_PERSONAL)
        return buildString {
            if (knowledge.isNotEmpty()) appendLine("App and website knowledge (read with knowledge_read when needed):\n" + knowledge.joinToString("\n") { line(it) })
            if (builtIn.isNotEmpty()) appendLine("Built-in skills (read with skill_read before using):\n" + builtIn.joinToString("\n") { line(it) })
            if (personal.isNotEmpty()) appendLine("Related personal skills (read with skill_read before using; verify trial results):\n" + personal.joinToString("\n"))
            append("This index is partial. Use skill_search to find other skills; skill_read can read any available skill by name.")
        }
    }
}

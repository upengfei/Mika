package com.wangzan101.mika.testworkspace

/** Local-only case discovery for the chat composer. It never calls a model. */
object TestCaseSuggestions {
    fun recommend(
        cases: List<ManagedTestCase>,
        query: String,
        recentSuccessfulCaseIds: List<String>,
        limit: Int = DEFAULT_LIMIT,
    ): List<ManagedTestCase> {
        if (limit <= 0) return emptyList()
        val active = cases.filterNot { it.archived }
        if (query.isBlank()) {
            val byId = active.associateBy { it.id }
            return recentSuccessfulCaseIds.distinct().mapNotNull(byId::get).take(limit)
        }

        val normalized = normalize(query)
        val words = tokens(normalized)
        if (words.isEmpty()) return emptyList()
        val successRank = recentSuccessfulCaseIds.withIndex().associate { it.value to it.index }
        return active.mapNotNull { record ->
            val contract = record.current.contract
            val fields = listOf(contract.name, contract.command, contract.targetApp.orEmpty(), contract.id) +
                record.labels + contract.steps.map { it.instruction }
            val normalizedFields = fields.map(::normalize).filter(String::isNotBlank)
            val score = when {
                normalizedFields.any { it == normalized } -> 1.0
                normalizedFields.any { it.contains(normalized) } -> 0.92
                else -> {
                    val fieldWords = normalizedFields.flatMap(::tokens).toSet()
                    words.intersect(fieldWords).size.toDouble() / words.size
                }
            }
            if (score < MIN_SCORE) null else Scored(record, score, successRank[record.id] ?: Int.MAX_VALUE)
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.successRank }
            .thenByDescending { it.case.current.savedAtMs }.thenBy { it.case.current.contract.name.lowercase() })
            .take(limit).map { it.case }
    }

    private data class Scored(val case: ManagedTestCase, val score: Double, val successRank: Int)

    private fun normalize(value: String) = value.trim().lowercase().replace(WHITESPACE, " ")
    private fun tokens(value: String) = TOKEN.findAll(value).map { it.value }.toSet()

    private val WHITESPACE = Regex("\\s+")
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    const val DEFAULT_LIMIT = 3
    const val MIN_SCORE = 0.6
}

package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.replay.Route

/** Optional product policy. Mika defaults retain the existing execution and learning behavior. */
interface RunExtensions {
    fun guideIndex(catalog: GuideCatalog, task: String, installed: List<Pair<String, String>>): String? = GuideRecommender.index(catalog, task)
    suspend fun directReplay(task: String): Boolean = false
    suspend fun allowReplay(task: String, route: Route): Boolean = true
    suspend fun completeReplay(task: String, route: Route): TaskState.Succeeded? = null
    fun acceptedCompletion(end: TaskState.Succeeded) = Unit

    object None : RunExtensions
}

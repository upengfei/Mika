package com.wangzan101.mika.core.skills

/** Product-specific automatic draft admission; the upstream five-action default stays independent. */
interface SkillLearningPolicy {
    val skipExtractionOnReplay: Boolean get() = false
    val minimumActions: Int get() = Eligibility.MIN_ACTIONS
    fun shouldDraft(input: DraftInput, existing: PersonalSkills): Boolean = true
    fun decorate(input: DraftInput, skill: PersonalSkill): PersonalSkill = skill

    object Default : SkillLearningPolicy
}

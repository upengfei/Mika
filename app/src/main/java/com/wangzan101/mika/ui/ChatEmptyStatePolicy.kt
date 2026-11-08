package com.wangzan101.mika.ui

/** Keep starter examples out of the way while the user is composing a new request. */
internal fun shouldShowChatEmptyState(hasNoExchanges: Boolean, hasBlockedState: Boolean, input: String): Boolean =
    hasNoExchanges && !hasBlockedState && input.isBlank()

/** Avoid adding an empty call-to-action on top of the starter examples. */
internal fun shouldShowTestCaseRecommendationTray(candidateCount: Int, input: String): Boolean =
    candidateCount > 0 || input.isNotBlank()

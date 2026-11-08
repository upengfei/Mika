package com.wangzan101.mika.ui

/** Keep starter examples out of the way while the user is composing a new request. */
internal fun shouldShowChatEmptyState(hasNoExchanges: Boolean, hasBlockedState: Boolean, input: String): Boolean =
    hasNoExchanges && !hasBlockedState && input.isBlank()

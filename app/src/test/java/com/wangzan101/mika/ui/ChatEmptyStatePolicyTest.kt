package com.wangzan101.mika.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEmptyStatePolicyTest {
    @Test
    fun starterExamplesDisappearWhileTypingIntoANewConversation() {
        assertFalse(shouldShowChatEmptyState(hasNoExchanges = true, hasBlockedState = false, input = "search cases"))
    }

    @Test
    fun starterExamplesRemainForAnUntouchedNewConversation() {
        assertTrue(shouldShowChatEmptyState(hasNoExchanges = true, hasBlockedState = false, input = "  "))
    }

    @Test
    fun blockedAndNonEmptyConversationsNeverShowStarterExamples() {
        assertFalse(shouldShowChatEmptyState(hasNoExchanges = true, hasBlockedState = true, input = ""))
        assertFalse(shouldShowChatEmptyState(hasNoExchanges = false, hasBlockedState = false, input = ""))
    }
}

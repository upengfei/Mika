package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.automation.TestTaskContract
import org.junit.Assert.assertEquals
import org.junit.Test

class TestCaseSuggestionsTest {
    @Test fun blankQueryShowsOnlyRecentSuccessfulCasesInRecentOrder() {
        val cases = listOf(case("login", "Login"), case("checkout", "Checkout"), case("search", "Search"))

        val got = TestCaseSuggestions.recommend(cases, "", listOf("search", "login"))

        assertEquals(listOf("search", "login"), got.map { it.id })
    }

    @Test fun queryRanksExactPhraseAndRecentSuccessBeforeOtherMatchesAndCapsAtThree() {
        val cases = listOf(
            case("checkout-a", "Checkout payment"),
            case("checkout-b", "Checkout payment"),
            case("checkout-c", "Checkout payment"),
            case("checkout-d", "Checkout payment"),
        )

        val got = TestCaseSuggestions.recommend(cases, "checkout payment", listOf("checkout-c", "checkout-b"))

        assertEquals(listOf("checkout-c", "checkout-b", "checkout-a"), got.map { it.id })
    }

    @Test fun unrelatedQueryHasNoHighConfidenceCandidates() {
        val got = TestCaseSuggestions.recommend(listOf(case("login", "Login")), "weather forecast", emptyList())

        assertEquals(emptyList<ManagedTestCase>(), got)
    }

    private fun case(id: String, name: String) = ManagedTestCase(
        id = id,
        projectId = TestCaseRepository.DEFAULT_PROJECT,
        suiteId = TestCaseRepository.DEFAULT_SUITE,
        revisions = listOf(TestCaseRevision(1, TestTaskContract(id = id, name = name, command = "Run $name"), 1L)),
    )
}

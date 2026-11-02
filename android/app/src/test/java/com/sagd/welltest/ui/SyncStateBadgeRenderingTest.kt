/*
 * UI tests — sync-state badge rendering (task 21.4).
 *
 * The per-record badge (`SyncStateBadge` / `SyncStateBadgeContent`) is a thin
 * composable: it draws a colored status dot plus a label for the record's
 * [SyncState] and, only for a failed record, a manual-retry control. Everything
 * it renders is a pure function of the state — the visible label comes from
 * [SyncStateBadgeViewModel.displayLabel], the assistive-technology description
 * from [SyncStateBadgeViewModel.accessibilityLabel], and whether the retry
 * control shows from [SyncStateBadgeUiState.showsRetryControl]. There is no
 * Compose UI test artifact on the unit-test classpath, so these tests pin down
 * exactly those rendering inputs for every sync state (Req 2.6, 2.7).
 *
 * Observation-driven updates and manual-retry forwarding are already covered by
 * SyncStateBadgeViewModelTest; this file focuses on what each state *renders*:
 *   - a distinct, non-blank label and accessibility description per state, and
 *   - the retry control appearing exactly for the failed state (Req 2.5, 7.7).
 */
package com.sagd.welltest.ui

import com.sagd.welltest.domain.SyncState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class SyncStateBadgeRenderingTest : StringSpec({

    "each sync state renders a distinct, non-blank badge label (Req 2.7)" {
        val labels = SyncState.entries.map { SyncStateBadgeViewModel.displayLabel(it) }
        labels.forEach { it.shouldNotBeBlank() }
        // One unique label per state so pending/synchronized/failed are
        // visually distinguishable in the badge.
        labels.toSet() shouldHaveSize SyncState.entries.size
    }

    "the badge label for each state matches the operator-facing wording (Req 2.7)" {
        SyncStateBadgeViewModel.displayLabel(SyncState.PENDING) shouldBe "Pending"
        SyncStateBadgeViewModel.displayLabel(SyncState.SYNCHRONIZED) shouldBe "Synced"
        SyncStateBadgeViewModel.displayLabel(SyncState.FAILED) shouldBe "Failed"
    }

    "each sync state renders a distinct, non-blank accessibility description (Req 2.7)" {
        val labels = SyncState.entries.map { SyncStateBadgeViewModel.accessibilityLabel(it) }
        labels.forEach { it.shouldNotBeBlank() }
        labels.toSet() shouldHaveSize SyncState.entries.size
    }

    "the accessibility description for each state matches the spoken wording (Req 2.7)" {
        SyncStateBadgeViewModel.accessibilityLabel(SyncState.PENDING) shouldBe "Sync pending"
        SyncStateBadgeViewModel.accessibilityLabel(SyncState.SYNCHRONIZED) shouldBe "Synchronized"
        SyncStateBadgeViewModel.accessibilityLabel(SyncState.FAILED) shouldBe "Sync failed"
    }

    "a pending badge renders without the retry control (Req 2.6)" {
        SyncStateBadgeUiState(SyncState.PENDING).showsRetryControl shouldBe false
    }

    "a synchronized badge renders without the retry control" {
        SyncStateBadgeUiState(SyncState.SYNCHRONIZED).showsRetryControl shouldBe false
    }

    "a failed badge renders the manual-retry control (Req 2.5, 7.7)" {
        SyncStateBadgeUiState(SyncState.FAILED).showsRetryControl shouldBe true
    }

    "the retry control shows for exactly one of the three sync states" {
        val withRetry = SyncState.entries.filter { SyncStateBadgeUiState(it).showsRetryControl }
        withRetry shouldBe listOf(SyncState.FAILED)
    }
})

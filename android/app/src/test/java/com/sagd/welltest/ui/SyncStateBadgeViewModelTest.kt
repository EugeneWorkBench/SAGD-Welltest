/*
 * Unit tests for [SyncStateBadgeViewModel] (task 21.3).
 *
 * Exercises the view model's observation of a single record's sync state and
 * its manual-retry forwarding:
 *   - it seeds with pending, the state of a freshly created record (Req 2.6);
 *   - it reflects each state the repository observation emits so the badge
 *     updates on a transition (Req 2.7);
 *   - the retry control shows exactly when the record has failed (Req 2.5, 7.7);
 *   - retry forwards to SyncRepository.manualRetry.
 *
 * A fake SyncRepository backs observeSyncState with a MutableStateFlow the test
 * drives directly; the view model collects it on the test dispatcher.
 */
package com.sagd.welltest.ui

import com.sagd.welltest.domain.SyncRepository
import com.sagd.welltest.domain.SyncState
import com.sagd.welltest.domain.SyncableRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.UUID

private class FakeSyncRepository(
    private val states: MutableStateFlow<SyncState> = MutableStateFlow(SyncState.PENDING),
) : SyncRepository {
    val manualRetryCalls = mutableListOf<UUID>()

    fun emit(state: SyncState) {
        states.value = state
    }

    override suspend fun nextPendingBatch(limit: Int): List<SyncableRecord> = emptyList()

    override suspend fun markSynchronized(recordId: UUID, remoteId: String) = Unit

    override suspend fun recordFailure(recordId: UUID) = Unit

    override suspend fun manualRetry(recordId: UUID) {
        manualRetryCalls += recordId
        // Model the repository resetting a failed record to pending (Req 2.5, 7.7).
        states.value = SyncState.PENDING
    }

    override fun observeSyncState(recordId: UUID): Flow<SyncState> = states
}

class SyncStateBadgeViewModelTest : StringSpec({

    val recordId = UUID.randomUUID()

    beforeSpec {
        Dispatchers.setMain(StandardTestDispatcher())
    }
    afterSpec {
        Dispatchers.resetMain()
    }

    "seeds with pending for a freshly created record (Req 2.6)" {
        val vm = SyncStateBadgeViewModel(recordId, FakeSyncRepository())
        // Before any collection the StateFlow carries its initial value.
        vm.uiState.value.state shouldBe SyncState.PENDING
        vm.uiState.value.showsRetryControl shouldBe false
    }

    "reflects a transition to synchronized emitted by the observation (Req 2.7)" {
        runTest {
            val repo = FakeSyncRepository()
            val vm = SyncStateBadgeViewModel(recordId, repo)
            repo.emit(SyncState.SYNCHRONIZED)
            // first() over the hot StateFlow starts a subscriber and drains the latest.
            val state = vm.uiState.first { it.state == SyncState.SYNCHRONIZED }
            state.state shouldBe SyncState.SYNCHRONIZED
            state.showsRetryControl shouldBe false
        }
    }

    "shows the retry control exactly when the record has failed (Req 2.5, 7.7)" {
        runTest {
            val repo = FakeSyncRepository()
            val vm = SyncStateBadgeViewModel(recordId, repo)
            repo.emit(SyncState.FAILED)
            val state = vm.uiState.first { it.state == SyncState.FAILED }
            state.showsRetryControl shouldBe true
        }
    }

    "retry forwards to SyncRepository.manualRetry and the record returns to pending (Req 2.5, 7.7)" {
        runTest {
            val repo = FakeSyncRepository()
            val vm = SyncStateBadgeViewModel(recordId, repo)
            repo.emit(SyncState.FAILED)
            vm.uiState.first { it.state == SyncState.FAILED }

            vm.retry()

            val state = vm.uiState.first { it.state == SyncState.PENDING }
            repo.manualRetryCalls shouldBe listOf(recordId)
            state.showsRetryControl shouldBe false
        }
    }
})

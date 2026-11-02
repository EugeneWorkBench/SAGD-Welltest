/*
 * Unit tests for [EscalationCaptureViewModel] (task 21.2).
 *
 * Exercises the view model orchestrating the real [DefaultEscalationValidator]
 * and a real [EscalationCaptureCoordinator] (wired over fake location and
 * repository seams — the pieces a UI test should not reach into a device for):
 *   - A missing priority and/or well blocks the save inline, naming exactly the
 *     offending fields and retaining the entered data (Req 7.2).
 *   - A valid save maps the coordinator's result to Saved, carrying the
 *     location-unavailable flag so the UI can note the geotag state (Req 7.1).
 *   - Storage-full and persistence-failure outcomes are surfaced with the
 *     entered data retained.
 *
 * Saves run on the test scheduler via an injected scope so results are
 * deterministic without touching a real dispatcher.
 */
package com.sagd.welltest.ui

import com.sagd.welltest.domain.DefaultEscalationValidator
import com.sagd.welltest.domain.Escalation
import com.sagd.welltest.domain.EscalationField
import com.sagd.welltest.domain.EscalationPriority
import com.sagd.welltest.domain.EscalationRepository
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.integrations.EscalationCaptureCoordinator
import com.sagd.welltest.integrations.LocationClock
import com.sagd.welltest.integrations.LocationProvider
import com.sagd.welltest.integrations.LocationResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EscalationCaptureViewModelTest : StringSpec({

    val wellA = Well(id = "WELL-1", name = "Pad A W1", type = WellType.PRODUCTION, updatedAt = Instant.EPOCH)

    /** A location provider with a fixed outcome. */
    class FakeLocationProvider(private val result: LocationResult) : LocationProvider {
        override suspend fun currentFix(): LocationResult = result
    }

    /** An escalation repository with a fixed save outcome, recording the last call. */
    class FakeEscalationRepository(private val result: SaveResult) : EscalationRepository {
        var saved: Escalation? = null
        override suspend fun saveEscalation(escalation: Escalation, photoData: ByteArray): SaveResult {
            saved = escalation
            return result
        }
    }

    val fixedClock = object : LocationClock {
        override fun now(): Instant = Instant.ofEpochSecond(1_700_000_000L)
    }

    fun coordinator(
        location: LocationResult,
        save: SaveResult,
    ): Pair<EscalationCaptureCoordinator, FakeEscalationRepository> {
        val repo = FakeEscalationRepository(save)
        val coord = EscalationCaptureCoordinator(
            validator = DefaultEscalationValidator(),
            locationProvider = FakeLocationProvider(location),
            repository = repo,
            clock = fixedClock,
        )
        return coord to repo
    }

    "starts editing" {
        val (coord, _) = coordinator(LocationResult.Unavailable, SaveResult.Success)
        val vm = EscalationCaptureViewModel(listOf(wellA), DefaultEscalationValidator(), coord)
        vm.uiState.value.submissionState shouldBe EscalationSubmissionState.Editing
    }

    "save with no priority and no well blocks inline naming both fields and keeps the photo (Req 7.2)" {
        val (coord, _) = coordinator(LocationResult.Unavailable, SaveResult.Success)
        val vm = EscalationCaptureViewModel(listOf(wellA), DefaultEscalationValidator(), coord)
        vm.attachPhoto(byteArrayOf(1, 2, 3))

        vm.save()

        val state = vm.uiState.value
        state.submissionState.shouldBeInstanceOf<EscalationSubmissionState.Invalid>()
        state.missingFields shouldContainExactlyInAnyOrder listOf(
            EscalationField.PRIORITY,
            EscalationField.WELL_ID,
        )
        // Entered data retained.
        state.hasPhoto shouldBe true
    }

    "save missing only the well names just the well (Req 7.2)" {
        val (coord, _) = coordinator(LocationResult.Unavailable, SaveResult.Success)
        val vm = EscalationCaptureViewModel(listOf(wellA), DefaultEscalationValidator(), coord)
        vm.attachPhoto(byteArrayOf(1))
        vm.selectPriority(EscalationPriority.HIGH)

        vm.save()

        vm.uiState.value.missingFields shouldContainExactlyInAnyOrder listOf(EscalationField.WELL_ID)
    }

    "a valid save with no fix maps to Saved with locationUnavailable true (Req 7.1)" {
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val (coord, repo) = coordinator(LocationResult.Unavailable, SaveResult.Success)
            val vm = EscalationCaptureViewModel(
                availableWells = listOf(wellA),
                validator = DefaultEscalationValidator(),
                coordinator = coord,
                scope = scope,
            )
            vm.attachPhoto(byteArrayOf(9))
            vm.selectPriority(EscalationPriority.MEDIUM)
            vm.selectWell(wellA.id)

            vm.save()
            vm.uiState.value.submissionState shouldBe EscalationSubmissionState.Saving
            testScheduler.advanceUntilIdle()

            val saved = vm.uiState.value.submissionState
            saved.shouldBeInstanceOf<EscalationSubmissionState.Saved>()
            saved.locationUnavailable shouldBe true
            repo.saved?.wellId shouldBe wellA.id
            repo.saved?.priority shouldBe EscalationPriority.MEDIUM
        }
    }

    "a valid save with a fix maps to Saved with locationUnavailable false (Req 7.1)" {
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val fix = com.sagd.welltest.integrations.LocationFix(
                latitude = 56.7,
                longitude = -111.4,
                timestamp = Instant.ofEpochSecond(1_700_000_000L),
            )
            val (coord, _) = coordinator(LocationResult.Available(fix), SaveResult.Success)
            val vm = EscalationCaptureViewModel(listOf(wellA), DefaultEscalationValidator(), coord, scope)
            vm.attachPhoto(byteArrayOf(9))
            vm.selectPriority(EscalationPriority.LOW)
            vm.selectWell(wellA.id)

            vm.save()
            testScheduler.advanceUntilIdle()

            val saved = vm.uiState.value.submissionState
            saved.shouldBeInstanceOf<EscalationSubmissionState.Saved>()
            saved.locationUnavailable shouldBe false
        }
    }

    "a storage-full save is surfaced and entered data retained" {
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val (coord, _) = coordinator(LocationResult.Unavailable, SaveResult.StorageFull)
            val vm = EscalationCaptureViewModel(listOf(wellA), DefaultEscalationValidator(), coord, scope)
            vm.attachPhoto(byteArrayOf(9))
            vm.selectPriority(EscalationPriority.HIGH)
            vm.selectWell(wellA.id)

            vm.save()
            testScheduler.advanceUntilIdle()

            vm.uiState.value.submissionState shouldBe EscalationSubmissionState.StorageFull
            vm.uiState.value.hasPhoto shouldBe true
        }
    }

    "a persistence failure is surfaced with its reason" {
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val (coord, _) = coordinator(LocationResult.Unavailable, SaveResult.Failure("disk error"))
            val vm = EscalationCaptureViewModel(listOf(wellA), DefaultEscalationValidator(), coord, scope)
            vm.attachPhoto(byteArrayOf(9))
            vm.selectPriority(EscalationPriority.HIGH)
            vm.selectWell(wellA.id)

            vm.save()
            testScheduler.advanceUntilIdle()

            val state = vm.uiState.value.submissionState
            state.shouldBeInstanceOf<EscalationSubmissionState.Failed>()
            state.reason shouldBe "disk error"
        }
    }

    "editing a selection after a blocked save clears the validation marks" {
        val (coord, _) = coordinator(LocationResult.Unavailable, SaveResult.Success)
        val vm = EscalationCaptureViewModel(listOf(wellA), DefaultEscalationValidator(), coord)
        vm.attachPhoto(byteArrayOf(1))
        vm.save() // blocked: missing priority + well
        vm.uiState.value.submissionState.shouldBeInstanceOf<EscalationSubmissionState.Invalid>()

        vm.selectPriority(EscalationPriority.HIGH)

        vm.uiState.value.submissionState shouldBe EscalationSubmissionState.Editing
        vm.uiState.value.missingFields shouldBe emptySet()
    }
})

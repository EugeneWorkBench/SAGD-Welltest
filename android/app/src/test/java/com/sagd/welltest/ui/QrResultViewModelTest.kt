/*
 * Unit tests for [QrResultViewModel] (task 21.2).
 *
 * Exercises the view model's mapping of a terminal [ScanOutcome] onto
 * [QrResultDisplayState]: a Found result carries the well, its tests (in the
 * order the repository supplied them, most-recent-first) and open tasks (Req
 * 6.2), annotated as local-storage-sourced when offline (Req 6.5); a NotFound /
 * UnavailableOffline miss and an Unreadable scan map to their branches (Req
 * 6.3, 6.6, 6.4); and rescan returns to the scanning state and re-triggers the
 * injected scan starter (Req 6.3, 6.4).
 */
package com.sagd.welltest.ui

import com.sagd.welltest.domain.MaintenanceStatus
import com.sagd.welltest.domain.MaintenanceTask
import com.sagd.welltest.domain.StaticConnectivityProvider
import com.sagd.welltest.domain.SyncMetadata
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellLookupResult
import com.sagd.welltest.domain.WellTest
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.integrations.ScanOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.UUID

class QrResultViewModelTest : StringSpec({

    val well = Well(id = "WELL-1", name = "Pad A W1", type = WellType.PRODUCTION, updatedAt = Instant.EPOCH)

    fun test(id: UUID, at: Instant) = WellTest(
        id = id,
        wellId = well.id,
        oilRatio = 0.5,
        waterRatio = 0.3,
        gasRatio = 0.2,
        capturedAt = at,
        sync = SyncMetadata(createdAt = at),
    )

    fun task(id: UUID) = MaintenanceTask(
        id = id,
        wellId = well.id,
        description = "Inspect pump",
        status = MaintenanceStatus.OPEN,
        createdAt = Instant.EPOCH,
    )

    "starts scanning" {
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = true))
        vm.uiState.value.displayState shouldBe QrResultDisplayState.Scanning
    }

    "a decoded Found outcome renders the well with its tests and open tasks in order (Req 6.2)" {
        val t1 = test(UUID.randomUUID(), Instant.ofEpochSecond(200))
        val t2 = test(UUID.randomUUID(), Instant.ofEpochSecond(100))
        val tk = task(UUID.randomUUID())
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = true))

        vm.onScanOutcome(
            ScanOutcome.Decoded(
                wellId = well.id,
                lookup = WellLookupResult.Found(well, listOf(t1, t2), listOf(tk)),
            ),
        )

        val display = vm.uiState.value.displayState
        display.shouldBeInstanceOf<QrResultDisplayState.Found>()
        display.well shouldBe well
        // Rendered in the exact order the repository supplied (most-recent-first).
        display.wellTests shouldBe listOf(t1, t2)
        display.openTasks shouldBe listOf(tk)
    }

    "an online Found result is not annotated as local storage (Req 6.5)" {
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = true))
        vm.onScanOutcome(
            ScanOutcome.Decoded(well.id, WellLookupResult.Found(well, emptyList(), emptyList())),
        )
        val display = vm.uiState.value.displayState
        display.shouldBeInstanceOf<QrResultDisplayState.Found>()
        display.fromLocalStorage shouldBe false
    }

    "an offline Found result is annotated as from local storage (Req 6.5)" {
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = false))
        vm.onScanOutcome(
            ScanOutcome.Decoded(well.id, WellLookupResult.Found(well, emptyList(), emptyList())),
        )
        val display = vm.uiState.value.displayState
        display.shouldBeInstanceOf<QrResultDisplayState.Found>()
        display.fromLocalStorage shouldBe true
    }

    "a NotFound lookup maps to the not-found branch (Req 6.3)" {
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = true))
        vm.onScanOutcome(ScanOutcome.Decoded("WELL-X", WellLookupResult.NotFound))
        vm.uiState.value.displayState shouldBe QrResultDisplayState.NotFound("WELL-X")
    }

    "an UnavailableOffline lookup maps to the offline branch (Req 6.6)" {
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = false))
        vm.onScanOutcome(ScanOutcome.Decoded("WELL-X", WellLookupResult.UnavailableOffline))
        vm.uiState.value.displayState shouldBe QrResultDisplayState.UnavailableOffline("WELL-X")
    }

    "an unreadable scan maps to the unreadable branch (Req 6.4)" {
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = true))
        vm.onScanOutcome(ScanOutcome.Unreadable)
        vm.uiState.value.displayState shouldBe QrResultDisplayState.Unreadable
    }

    "rescan returns to scanning and re-triggers the scan starter (Req 6.3, 6.4)" {
        var starts = 0
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = true), startScan = { starts++ })
        vm.onScanOutcome(ScanOutcome.Unreadable)

        vm.rescan()

        vm.uiState.value.displayState shouldBe QrResultDisplayState.Scanning
        starts shouldBe 1
    }

    "begin enters scanning and triggers the scan starter" {
        var starts = 0
        val vm = QrResultViewModel(StaticConnectivityProvider(isOnline = true), startScan = { starts++ })
        vm.begin()
        vm.uiState.value.displayState shouldBe QrResultDisplayState.Scanning
        starts shouldBe 1
    }
})

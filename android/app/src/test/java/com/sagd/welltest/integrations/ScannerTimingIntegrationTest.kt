/*
 * Integration test — scanner timing (Android) — task 19.5.
 *
 * _Requirements: 6.1, 6.4_
 *
 * This is the Android mirror of the iOS ScannerTimingIntegrationTests (task
 * 18.5). It exercises the real, platform-agnostic [QrScanController] (task 19.1)
 * — the core of the Android QR scanner — end to end through its production seams:
 * a controllable [ScannerClock] standing in for the monotonic uptime source and
 * a stub [WellLookupUseCase] standing in for the local-store lookup. There is NO
 * CameraX, NO ML Kit, NO Android framework, and NO real waiting; both timing
 * guarantees are asserted by driving the virtual clock explicitly.
 *
 * The two guarantees, matching the design "QR Scanning Flow":
 *
 *   * Req 6.1 — WHEN a code is scanned, the App SHALL decode the well identifier
 *     within 2 seconds of the code entering the camera view. Case 1 advances the
 *     virtual clock less than the 2-second decode budget and then delivers a
 *     decoded id via [QrScanController.onDecoded]; the controller must emit
 *     [ScanOutcome.Decoded] carrying the decoded id and the stubbed lookup, and
 *     [QrScanController.isWithinDecodeBudget] must still report true at the
 *     moment of decode.
 *
 *   * Req 6.4 — IF the Scanner cannot decode a code within 10 seconds, the App
 *     SHALL report the scan unreadable and present a rescan control. Case 2
 *     advances the virtual clock past the 10-second timeout and calls
 *     [QrScanController.onTimeoutCheck]; the controller must emit
 *     [ScanOutcome.Unreadable]. A subsequent [QrScanController.rescan] must
 *     re-arm the controller (the rescan control required by Req 6.4) so a later
 *     in-budget decode yields [ScanOutcome.Decoded] again.
 *
 * Why direct method-driving rather than racing a decode stream (as iOS does):
 * the Android controller is deliberately event-driven and non-blocking — the
 * camera/ML-Kit shell feeds it decoded strings via [onDecoded] and a periodic
 * timeout tick via [onTimeoutCheck], and the controller reads the injected
 * [ScannerClock] to decide the outcome. The integration test plays the role of
 * that shell on a single thread, advancing the controllable clock to the instant
 * each event would occur. This matches the controller's documented threading
 * contract (a single serialized caller) and keeps timing fully virtual.
 */
package com.sagd.welltest.integrations

import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellLookupResult
import com.sagd.welltest.domain.WellLookupUseCase
import com.sagd.welltest.domain.WellType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScannerTimingIntegrationTest : StringSpec({

    // ---- Case 1 — decode within the 2-second budget (Req 6.1) ----

    "a decode delivered within the 2s budget yields Decoded and is within budget (Req 6.1)" {
        runTest {
            val clock = ControllableScannerClock(startMillis = SCAN_START_MILLIS)
            val well = Well(
                id = "WELL-123",
                name = "Scanner Well",
                type = WellType.PRODUCTION,
                padId = null,
                updatedAt = Instant.ofEpochSecond(1_700_000_000L),
            )
            val lookup = StubWellLookupUseCase(
                WellLookupResult.Found(
                    well = well,
                    wellTests = emptyList(),
                    openMaintenanceTasks = emptyList(),
                ),
            )
            val sink = OutcomeSink()
            val controller = QrScanController(
                lookupUseCase = lookup,
                onOutcome = sink::accept,
                clock = clock,
            )

            controller.start()

            // The code enters view and decodes 1s in — inside the 2s budget.
            clock.advance(1_000L)
            withClue("a decode 1s into the session must be within the 2-second budget (Req 6.1)") {
                controller.isWithinDecodeBudget().shouldBeTrue()
            }

            val accepted = controller.onDecoded("WELL-123")
            withClue("the first in-budget decode must be accepted and terminate the session") {
                accepted.shouldBeTrue()
            }

            val outcome = sink.only()
            val decoded = outcome.shouldBeInstanceOf<ScanOutcome.Decoded>()
            decoded.wellId shouldBe "WELL-123"
            decoded.lookup shouldBe WellLookupResult.Found(
                well = well,
                wellTests = emptyList(),
                openMaintenanceTasks = emptyList(),
            )
            // The lookup identified the known id as Found; the controller passed
            // it through verbatim rather than re-deriving the branch.
            lookup.calls shouldBe listOf("WELL-123")
        }
    }

    "an in-budget decode onto an unknown id still yields Decoded carrying NotFound (Req 6.1)" {
        runTest {
            val clock = ControllableScannerClock(startMillis = SCAN_START_MILLIS)
            val lookup = StubWellLookupUseCase(WellLookupResult.NotFound)
            val sink = OutcomeSink()
            val controller = QrScanController(
                lookupUseCase = lookup,
                onOutcome = sink::accept,
                clock = clock,
            )

            controller.start()
            // Decode lands half a second in — comfortably inside the budget.
            clock.advance(500L)
            controller.isWithinDecodeBudget().shouldBeTrue()

            controller.onDecoded("WELL-UNKNOWN").shouldBeTrue()

            val decoded = sink.only().shouldBeInstanceOf<ScanOutcome.Decoded>()
            decoded.wellId shouldBe "WELL-UNKNOWN"
            // The budget is about decode timing, independent of the lookup branch.
            decoded.lookup shouldBe WellLookupResult.NotFound
        }
    }

    // ---- Case 2 — unreadable after the 10-second budget, then rescan (Req 6.4) ----

    "no decode before the 10s timeout yields Unreadable, and rescan re-arms to a fresh Decoded (Req 6.4)" {
        runTest {
            val clock = ControllableScannerClock(startMillis = SCAN_START_MILLIS)
            val well = Well(
                id = "WELL-123",
                name = "Scanner Well",
                type = WellType.PRODUCTION,
                padId = null,
                updatedAt = Instant.ofEpochSecond(1_700_000_000L),
            )
            val lookup = StubWellLookupUseCase(
                WellLookupResult.Found(
                    well = well,
                    wellTests = emptyList(),
                    openMaintenanceTasks = emptyList(),
                ),
            )
            val sink = OutcomeSink()
            val controller = QrScanController(
                lookupUseCase = lookup,
                onOutcome = sink::accept,
                clock = clock,
            )

            controller.start()

            // Before the deadline a timeout tick is a no-op: nothing is emitted
            // and the session is still scanning.
            clock.advance(9_000L)
            withClue("a timeout check before 10s must not end the session (Req 6.4)") {
                controller.onTimeoutCheck() shouldBe false
            }
            sink.isEmpty().shouldBeTrue()

            // At/after 10s with no decode, the timeout check ends the session as
            // unreadable (Req 6.4).
            clock.advance(1_000L) // now exactly 10s since start
            withClue("the 10s timeout with no decode must end the session unreadable (Req 6.4)") {
                controller.onTimeoutCheck().shouldBeTrue()
            }
            sink.only() shouldBe ScanOutcome.Unreadable
            // The stub lookup was never consulted — nothing ever decoded.
            lookup.calls shouldBe emptyList()

            // The rescan control re-arms the controller: a fresh session starts
            // and its budgets are measured from the rescan instant.
            sink.reset()
            controller.rescan()
            withClue("rescan must re-arm the controller to a scanning state") {
                controller.isScanning.shouldBeTrue()
            }

            // A decode 1s into the re-armed session is within the restarted 2s
            // budget and yields Decoded (Req 6.1, 6.4).
            clock.advance(1_000L)
            controller.isWithinDecodeBudget().shouldBeTrue()
            controller.onDecoded("WELL-123").shouldBeTrue()

            val decoded = sink.only().shouldBeInstanceOf<ScanOutcome.Decoded>()
            decoded.wellId shouldBe "WELL-123"
            decoded.lookup.shouldBeInstanceOf<WellLookupResult.Found>()
            lookup.calls shouldBe listOf("WELL-123")
        }
    }
}) {
    companion object {
        /** An arbitrary non-zero monotonic start reading; only differences matter. */
        private const val SCAN_START_MILLIS = 5_000L
    }
}

// ---------------------------------------------------------------------------
// Controllable scanner clock (virtual monotonic time, advanceable on command)
// ---------------------------------------------------------------------------

/**
 * A [ScannerClock] reporting a virtual monotonic reading in milliseconds,
 * advanceable only via [advance]. The test plays the camera/scheduler shell and
 * advances the clock to the instant each event (decode / timeout tick) would
 * occur, so the decode-budget and unreadable-timeout deadlines are reached
 * deterministically with no real waiting. Thread-safe out of caution, though the
 * controller is driven from a single sequence.
 */
private class ControllableScannerClock(startMillis: Long) : ScannerClock {
    private val current = AtomicLong(startMillis)

    override fun elapsedMillis(): Long = current.get()

    /** Advance virtual time by [millis] (must be non-negative to stay monotonic). */
    fun advance(millis: Long) {
        require(millis >= 0) { "scanner clock must be monotonic non-decreasing" }
        current.addAndGet(millis)
    }
}

// ---------------------------------------------------------------------------
// Stub well lookup use case (deterministic, records calls)
// ---------------------------------------------------------------------------

/**
 * A deterministic [WellLookupUseCase] returning a fixed result for any id, so
 * the controller's decode path yields a known Found / NotFound /
 * UnavailableOffline branch without touching a store or the network. It records
 * the ids it was asked to look up so the test can assert the controller consults
 * the use case exactly once on the winning decode (and never when nothing
 * decodes).
 */
private class StubWellLookupUseCase(private val result: WellLookupResult) : WellLookupUseCase {
    private val recorded = mutableListOf<String>()

    val calls: List<String>
        @Synchronized get() = recorded.toList()

    @Synchronized
    override suspend fun lookup(wellId: String): WellLookupResult {
        recorded.add(wellId)
        return result
    }
}

// ---------------------------------------------------------------------------
// Outcome sink (captures the controller's terminal outcome(s))
// ---------------------------------------------------------------------------

/**
 * Captures outcomes emitted by the controller's `onOutcome` callback. The
 * controller emits exactly one outcome per session; [reset] clears the capture
 * between a session and its rescan so each phase can be asserted independently.
 */
private class OutcomeSink {
    private val captured = AtomicReference<MutableList<ScanOutcome>>(mutableListOf())

    @Synchronized
    suspend fun accept(outcome: ScanOutcome) {
        captured.get().add(outcome)
    }

    @Synchronized
    fun isEmpty(): Boolean = captured.get().isEmpty()

    /** The single captured outcome; fails if zero or more than one was emitted. */
    @Synchronized
    fun only(): ScanOutcome {
        val list = captured.get()
        check(list.size == 1) { "expected exactly one outcome, captured ${list.size}: $list" }
        return list.single()
    }

    /** Clear captured outcomes (used between a session and its rescan). */
    @Synchronized
    fun reset() {
        captured.set(mutableListOf())
    }
}

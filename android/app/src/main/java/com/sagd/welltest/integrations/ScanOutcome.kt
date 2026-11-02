/*
 * QR scan outcome.
 *
 * The terminal result of a single scan session, surfaced from the core
 * [QrScanController] to the UI. It composes the two orthogonal concerns of the
 * QR Scanning Flow (design.md "QR Scanning Flow"):
 *
 *   1. Whether a code was decoded at all within the 10-second budget. A session
 *      that never decodes ends as [Unreadable] with a rescan control (Req 6.4).
 *   2. If a code *was* decoded, the result of looking the identifier up against
 *      the local store via `WellLookupUseCase` — carried verbatim as a
 *      [com.sagd.welltest.domain.WellLookupResult] so the Found / NotFound /
 *      UnavailableOffline branches (Req 6.2, 6.3, 6.5, 6.6) are decided by the
 *      domain layer, not re-derived here.
 *
 * Modeled as a sealed hierarchy so the UI handles every outcome exhaustively,
 * matching the house style of the domain result types.
 *
 * Introduced by task 19.1.
 */
package com.sagd.welltest.integrations

import com.sagd.welltest.domain.WellLookupResult

/**
 * The terminal outcome of one scan session.
 *
 * - [Decoded] — a code was decoded within budget and its identifier looked up;
 *   [wellId] is the decoded string and [lookup] is the domain lookup result
 *   that drives the result screen (Req 6.2, 6.3, 6.5, 6.6).
 * - [Unreadable] — no code decoded within the 10-second budget; the UI shows an
 *   "unreadable" message and a rescan control (Req 6.4).
 */
sealed interface ScanOutcome {
    data class Decoded(
        val wellId: String,
        val lookup: WellLookupResult,
    ) : ScanOutcome

    data object Unreadable : ScanOutcome
}

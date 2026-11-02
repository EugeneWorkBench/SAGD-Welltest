/*
 * Transaction seam for the transactional repositories.
 *
 * [LogRepositoryImpl] and [EscalationRepositoryImpl] need an all-or-nothing
 * write boundary: the body either commits in full or, on any thrown error,
 * rolls back leaving the prior persisted state untouched (Req 1.4). In
 * production that boundary is a single Room `@Transaction` obtained via
 * `androidx.room.withTransaction`, which requires a live [AppDatabase] and the
 * Android SQLite runtime.
 *
 * Depending on `withTransaction` directly makes the save paths impossible to
 * exercise in a pure-JVM unit test (no device, no Robolectric): Room's
 * in-memory builder still needs a `Context` and the native SQLite library. This
 * tiny seam lets the repositories keep the real transactional semantics in
 * production while a test can supply a runner that faithfully reproduces the
 * two behaviours the atomic-save property cares about — commit on success,
 * propagate the throw (i.e. roll back) on failure — over fake DAOs.
 *
 * The production implementation ([roomTransactionRunner]) is a thin pass-through
 * to `AppDatabase.withTransaction`, so wrapping it changes nothing at runtime.
 */
package com.sagd.welltest.repository

import androidx.room.withTransaction
import com.sagd.welltest.localstore.AppDatabase

/**
 * Runs a suspend [block] inside a single atomic transaction.
 *
 * Contract (matching Room's `withTransaction`):
 *   - If [block] returns normally, its writes are committed and the value is
 *     returned.
 *   - If [block] throws, the transaction is rolled back — none of its writes
 *     are visible afterwards — and the throwable propagates to the caller.
 *
 * The repositories rely only on this contract, never on the concrete Room type,
 * so any implementation that honours commit-on-success / rollback-on-throw is a
 * sound substitute.
 */
fun interface TransactionRunner {
    suspend fun <R> withTransaction(block: suspend () -> R): R
}

/**
 * The production [TransactionRunner]: a direct pass-through to
 * `AppDatabase.withTransaction`, giving the repositories exactly the Room
 * transaction they had before this seam existed.
 */
fun roomTransactionRunner(db: AppDatabase): TransactionRunner =
    TransactionRunner { block -> db.withTransaction { block() } }

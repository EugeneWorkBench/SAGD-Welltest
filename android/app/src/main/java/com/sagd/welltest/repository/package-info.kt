/**
 * Repository layer.
 *
 * The only component the UI uses to read/write data. Wraps the local store,
 * assigns sync metadata on write, signals the sync engine that new work exists,
 * and exposes observable (Flow) queries so the UI updates reactively.
 * Implementations back WellRepository, LogRepository, EscalationRepository,
 * and SyncRepository over Room DAOs.
 */
package com.sagd.welltest.repository

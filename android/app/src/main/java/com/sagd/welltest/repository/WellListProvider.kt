/*
 * Well-list provider.
 *
 * The well-selection screen needs the full set of wells to choose from, but the
 * domain [com.sagd.welltest.domain.WellRepository] contract is intentionally
 * narrow (lookup by id, plus the records attached to a well) because the QR-
 * lookup use case never needs a full list. This tiny provider supplies the list
 * for the UI composition root without widening that contract, mirroring the iOS
 * `GRDBWellListProvider` which plays the same role next to the GRDB repository.
 *
 * It lives in the repository layer because the WellEntity -> domain mapper is
 * package-internal here, keeping the UI dependent on the domain [Well] type
 * alone.
 *
 * Introduced by task 23.2 (composition root).
 */
package com.sagd.welltest.repository

import com.sagd.welltest.domain.Well
import com.sagd.welltest.localstore.WellDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Observes the full well list for the selection screen, name-ordered. */
class WellListProvider(
    private val wellDao: WellDao,
) {
    /** All wells, name-ordered; re-emits when the pulled reference set changes. */
    fun observeAll(): Flow<List<Well>> =
        wellDao.observeAll().map { entities -> entities.map { it.toDomain() } }
}

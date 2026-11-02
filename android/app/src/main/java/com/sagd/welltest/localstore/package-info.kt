/**
 * Local store layer.
 *
 * SQLite via Room (entities, DAOs, migrations) plus a file-based blob store for
 * escalation photos. Single source of truth on-device; opened on launch so
 * persisted records reload after restart.
 */
package com.sagd.welltest.localstore

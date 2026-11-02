/*
 * UI layer — global offline-indicator banner.
 *
 * Thin Jetpack Compose banner bound directly to the sync layer's
 * [OfflineIndicator] StateFlow. The sync engine flips the indicator to offline
 * within 5 seconds of a connectivity loss (Req 2.8); this banner simply
 * reflects the published value — it holds no connectivity logic of its own, so
 * no view model is needed between it and the StateFlow.
 *
 * Mirrors the iOS task 20.3 global offline indicator. Rendered with material3
 * primitives only so no material-icons artifact is required.
 *
 * Implemented by task 21.3.
 * Requirements: 2.8.
 */
package com.sagd.welltest.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sagd.welltest.sync.OfflineIndicator

/**
 * Global offline banner bound to the [OfflineIndicator]. Shown while the device
 * is offline and hidden when connectivity is present. The indicator's StateFlow
 * emits the current value immediately, so the banner reflects the live offline
 * state on first composition and updates within the engine's 5-second flip
 * budget on a loss (Req 2.8).
 */
@Composable
fun OfflineIndicatorBanner(
    offlineIndicator: OfflineIndicator,
    modifier: Modifier = Modifier,
) {
    val offline by offlineIndicator.observe().collectAsState()
    OfflineIndicatorBannerContent(offline = offline, modifier = modifier)
}

/**
 * Stateless banner body. Split out so it renders from a plain boolean without
 * the sync dependency — useful for previews and UI tests.
 */
@Composable
fun OfflineIndicatorBannerContent(
    offline: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = offline, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Offline. Captured data will sync when connectivity returns."
                },
        ) {
            Text(
                text = "Offline — changes will sync when you reconnect",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

package com.sagd.welltest

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.property.PropertyTesting

/**
 * Project-wide kotest configuration.
 *
 * Every property-based test defaults to a minimum of 100 iterations, matching
 * the design's testing strategy (each of the 24 correctness properties runs at
 * least 100 iterations). Individual tests may raise this via their own
 * [io.kotest.property.PropTestConfig], but never go below the project default.
 */
object KotestProjectConfig : AbstractProjectConfig() {

    /** Minimum iterations for every property-based test. */
    const val MIN_PROPERTY_ITERATIONS: Int = 100

    override suspend fun beforeProject() {
        PropertyTesting.defaultIterationCount = MIN_PROPERTY_ITERATIONS
    }
}

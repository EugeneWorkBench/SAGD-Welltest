package com.sagd.welltest

import io.kotest.property.PropTestConfig

/**
 * Shared property-based-testing helper.
 *
 * Use [pbtConfig] when a property test needs an explicit [PropTestConfig] so it
 * never drops below the project-wide minimum of 100 iterations.
 */
fun pbtConfig(iterations: Int = KotestProjectConfig.MIN_PROPERTY_ITERATIONS): PropTestConfig =
    PropTestConfig(iterations = maxOf(iterations, KotestProjectConfig.MIN_PROPERTY_ITERATIONS))

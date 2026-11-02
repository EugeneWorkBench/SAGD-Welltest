package com.sagd.welltest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.PropertyTesting
import io.kotest.property.checkAll

/**
 * Smoke test confirming the unit test module and kotest property config are
 * wired up: the default property iteration count is at least 100.
 */
class ProjectConfigTest : StringSpec({

    "default property iteration count is at least 100" {
        PropertyTesting.defaultIterationCount shouldBeGreaterThanOrEqual 100
    }

    "pbtConfig never drops below the project minimum" {
        pbtConfig(10).iterations shouldBe 100
        pbtConfig(250).iterations shouldBe 250
    }

    "property-based testing runs over generated inputs" {
        checkAll<Int, Int> { a, b ->
            (a + b) shouldBe (b + a)
        }
    }
})

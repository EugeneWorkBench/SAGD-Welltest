/**
 * Domain / use-case layer.
 *
 * Pure, platform-agnostic logic: SubcoolEstimator, production/injection log
 * validators, well-lookup use case, and escalation assembly. Deterministic
 * functions over their inputs with no I/O; the primary property-based-testing
 * target. Depends on the repository layer only through interfaces.
 */
package com.sagd.welltest.domain

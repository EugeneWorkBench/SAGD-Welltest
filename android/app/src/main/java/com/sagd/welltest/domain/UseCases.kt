/*
 * Pure domain / use-case contracts.
 *
 * These are deterministic functions over their inputs with no I/O (the
 * SubcoolEstimator and validators) plus the well-lookup use case, which reads
 * through the repository. They mirror the iOS protocols exactly and are the
 * primary target for property-based tests. Implementations arrive in the
 * domain-logic tasks (11, 12, 13). See design.md "Domain components".
 */
package com.sagd.welltest.domain

/**
 * Subcool estimation over an injected steam table and flashing threshold (Req 5).
 */
interface SubcoolEstimating {
    /**
     * Computes subcool from a casing pressure and pump temperature.
     * subcool = saturationTemperature(casingPressure) - pumpTemperature (Req 5.1).
     */
    fun estimate(casingPressure: Double, pumpTemperature: Double): SubcoolResult

    /** Interpolated saturation temperature, or null outside the steam-table domain (Req 5.4). */
    fun saturationTemperature(pressure: Double): Double?
}

/**
 * Validates raw production-log input, normalizing to a [WellheadLog] on success
 * (Req 3.2-3.6).
 */
interface ProductionLogValidator {
    fun validate(fields: ProductionLogInput): ValidationResult<WellheadLog>
}

/**
 * Validates raw injection-log input, normalizing to a [WellheadLog] on success
 * (Req 4.2-4.5).
 */
interface InjectionLogValidator {
    fun validate(fields: InjectionLogInput): ValidationResult<WellheadLog>
}

/**
 * Validates raw escalation input: priority and well are both required; echoes
 * the input back on success (Req 7.2).
 */
interface EscalationValidator {
    fun validate(input: EscalationInput): ValidationResult<EscalationInput>
}

/**
 * QR well lookup against the local store. The result is identical regardless of
 * connectivity when the well exists locally (Req 6.5).
 */
interface WellLookupUseCase {
    suspend fun lookup(wellId: String): WellLookupResult
}

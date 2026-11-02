/*
 * Raw, operator-entered inputs handed to the pure validators.
 *
 * Fields are captured as the raw strings the operator typed so the validators
 * can enforce numeric-format rules (digits, at most one decimal point, optional
 * leading minus) before range checks run. Normalization into the typed domain
 * records happens only after validation succeeds. See design.md
 * "Components and Interfaces" (Validators) and Req 3.3 / 4.2.
 */
package com.sagd.welltest.domain

/** Raw production-log form input (Req 3). */
data class ProductionLogInput(
    val wellId: String,
    val emulsionFlowRate: String,
    val casingGasPressure: String,
    val espMotorTemperature: String,
    val waterCutPercent: String,
)

/** Raw injection-log form input (Req 4). */
data class InjectionLogInput(
    val wellId: String,
    val steamInjectionPressure: String,
    val steamTemperature: String,
    val steamFlowRate: String,
)

/** Raw escalation form input (Req 7.2). */
data class EscalationInput(
    val wellId: String?,
    val priority: EscalationPriority?,
    val photoRef: String?,
)

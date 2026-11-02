/*
 * UI tests — well-test capture form rendering per well type (task 21.4).
 *
 * The well-selection + wellhead-log form screens are thin: `WellheadLogScreen`
 * shows the selection list until a well is chosen, then renders the production
 * or injection field set chosen entirely by [WellType] (Req 3.1 / 4.1). All of
 * that rendering is driven by [WellheadLogViewModel]'s state, so these tests
 * assert the state transitions and validation routing that *decide* what the
 * composable draws rather than reaching into Compose — there is no Compose UI
 * test artifact on the unit-test classpath, and the field set is a pure
 * function of the view-model state.
 *
 * Coverage:
 *   - Before a well is selected the form is not shown (selection screen renders).
 *   - Selecting a production well presents the production form (Req 3.1); a save
 *     with every field empty flags exactly the four production fields — the
 *     fields the production form renders — and none of the injection fields.
 *   - Selecting an injection well presents the injection form (Req 4.1); a save
 *     with every field empty flags exactly the three injection fields.
 *   - Switching wells clears the previous form's values and errors so a stale
 *     field never bleeds across the type boundary.
 *   - Entered values are retained verbatim behind a blocked save (Req 3.2-3.5,
 *     4.2-4.4) so the form re-renders them for correction.
 *
 * Saves run on an injected test scope so the (local, synchronous-from-the-UI's
 * point-of-view) persistence is deterministic; a fake [LogRepository] records
 * what it was handed.
 */
package com.sagd.welltest.ui

import com.sagd.welltest.domain.DefaultInjectionLogValidator
import com.sagd.welltest.domain.DefaultProductionLogValidator
import com.sagd.welltest.domain.LogRepository
import com.sagd.welltest.domain.SaveResult
import com.sagd.welltest.domain.Well
import com.sagd.welltest.domain.WellType
import com.sagd.welltest.domain.WellheadLog
import com.sagd.welltest.domain.WellheadLogField
import com.sagd.welltest.domain.WellTest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WellheadLogFormRenderingTest : StringSpec({

    val productionWell =
        Well(id = "WELL-P", name = "Pad A W1", type = WellType.PRODUCTION, updatedAt = Instant.EPOCH)
    val injectionWell =
        Well(id = "WELL-I", name = "Pad B I1", type = WellType.INJECTION, updatedAt = Instant.EPOCH)

    /** A log repository with a fixed save outcome that records the last log it was handed. */
    class FakeLogRepository(private val result: SaveResult = SaveResult.Success) : LogRepository {
        var savedLog: WellheadLog? = null
        override suspend fun saveWellheadLog(log: WellheadLog): SaveResult {
            savedLog = log
            return result
        }

        override suspend fun saveWellTest(test: WellTest): SaveResult = result
    }

    fun viewModel(
        repository: LogRepository = FakeLogRepository(),
        scope: TestScope? = null,
    ): WellheadLogViewModel = WellheadLogViewModel(
        logRepository = repository,
        productionValidator = DefaultProductionLogValidator(),
        injectionValidator = DefaultInjectionLogValidator(),
        scope = scope,
    )

    // The exact field identifiers each form renders.
    val productionFields = listOf(
        WellheadLogField.EMULSION_FLOW_RATE,
        WellheadLogField.CASING_GAS_PRESSURE,
        WellheadLogField.ESP_MOTOR_TEMPERATURE,
        WellheadLogField.WATER_CUT_PERCENT,
    )
    val injectionFields = listOf(
        WellheadLogField.STEAM_INJECTION_PRESSURE,
        WellheadLogField.STEAM_TEMPERATURE,
        WellheadLogField.STEAM_FLOW_RATE,
    )

    "before a well is selected the log form is not rendered (selection screen shows)" {
        val vm = viewModel()
        val state = vm.uiState.value
        state.selectedWell shouldBe null
        state.isEditingLog shouldBe false
    }

    "selecting a production well presents the production form (Req 3.1)" {
        val vm = viewModel()
        vm.selectWell(productionWell)

        val state = vm.uiState.value
        state.selectedWell shouldBe productionWell
        state.selectedWell?.type shouldBe WellType.PRODUCTION
        state.isEditingLog shouldBe true
        // A freshly presented form starts clear and with no stale status.
        state.fieldErrors.shouldBeEmpty()
        state.saveStatus shouldBe SaveStatus.Idle
    }

    "selecting an injection well presents the injection form (Req 4.1)" {
        val vm = viewModel()
        vm.selectWell(injectionWell)

        val state = vm.uiState.value
        state.selectedWell?.type shouldBe WellType.INJECTION
        state.isEditingLog shouldBe true
    }

    "the production form renders exactly the four production fields (Req 3.1)" {
        val vm = viewModel()
        vm.selectWell(productionWell)

        // Saving an all-empty production form routes through the production
        // validator and flags exactly the fields that form renders — and none
        // of the injection fields — confirming the rendered field set per type.
        vm.save()

        val errors = vm.uiState.value.fieldErrors
        errors.keys shouldContainExactlyInAnyOrder productionFields
        injectionFields.forEach { errors.keys.toList() shouldNotContain it }
        vm.uiState.value.saveStatus shouldBe SaveStatus.Idle
    }

    "the injection form renders exactly the three injection fields (Req 4.1)" {
        val vm = viewModel()
        vm.selectWell(injectionWell)

        vm.save()

        val errors = vm.uiState.value.fieldErrors
        errors.keys shouldContainExactlyInAnyOrder injectionFields
        productionFields.forEach { errors.keys.toList() shouldNotContain it }
    }

    "switching from a production well to an injection well clears the previous form" {
        val vm = viewModel()
        vm.selectWell(productionWell)
        vm.onEmulsionFlowRateChange("123")
        vm.save() // flags the remaining empty production fields

        vm.uiState.value.fieldErrors.isEmpty() shouldBe false

        vm.selectWell(injectionWell)

        val state = vm.uiState.value
        state.selectedWell?.type shouldBe WellType.INJECTION
        // No production value or error survives the switch.
        state.emulsionFlowRate shouldBe ""
        state.fieldErrors.shouldBeEmpty()
        state.saveStatus shouldBe SaveStatus.Idle
    }

    "a blocked save retains entered values so the form re-renders them (Req 3.2-3.5)" {
        val vm = viewModel()
        vm.selectWell(productionWell)
        vm.onEmulsionFlowRateChange("42.5")
        vm.onWaterCutPercentChange("150") // out of [0,100]

        vm.save()

        val state = vm.uiState.value
        // Values are echoed back verbatim.
        state.emulsionFlowRate shouldBe "42.5"
        state.waterCutPercent shouldBe "150"
        // Water cut is flagged; the valid emulsion field is not; the still-empty
        // fields are flagged too.
        state.fieldErrors.keys shouldContainExactlyInAnyOrder listOf(
            WellheadLogField.CASING_GAS_PRESSURE,
            WellheadLogField.ESP_MOTOR_TEMPERATURE,
            WellheadLogField.WATER_CUT_PERCENT,
        )
    }

    "a fully valid production form persists a production log (Req 1.1)" {
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val repo = FakeLogRepository(SaveResult.Success)
            val vm = viewModel(repository = repo, scope = scope)
            vm.selectWell(productionWell)
            vm.onEmulsionFlowRateChange("100.0")
            vm.onCasingGasPressureChange("200.0")
            vm.onEspMotorTemperatureChange("75.0")
            vm.onWaterCutPercentChange("30")

            vm.save()
            vm.uiState.value.saveStatus shouldBe SaveStatus.Saving
            testScheduler.advanceUntilIdle()

            vm.uiState.value.saveStatus.shouldBeInstanceOf<SaveStatus.Saved>()
            vm.uiState.value.isSaved shouldBe true
            repo.savedLog?.wellId shouldBe productionWell.id
        }
    }
})

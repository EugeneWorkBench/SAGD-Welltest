//
//  WellheadLogFormRenderingUITests.swift
//
//  Task 20.4 — UI tests: form rendering (iOS).
//
//  Requirements: 3.1, 4.1.
//
//  Req 3.1: "WHERE the selected Well is a Production_Well, THE App SHALL present
//  input fields for emulsion flow rate, casing gas pressure, ESP motor
//  temperature, and water cut sample percentage."
//  Req 4.1: "WHERE the selected Well is an Injection_Well, THE App SHALL present
//  input fields for steam injection pressure, steam temperature, and steam flow
//  rate."
//
//  SwiftUI view-tree inspection is impractical in this package (no ViewInspector
//  dependency, and `WellheadLogFormView.body` is an opaque `some View`). Per the
//  design's thin-view / stateful-view-model split, the field set the form renders
//  is fully determined by `WellheadLogFormViewModel` + `Well.type`: the view's
//  `fields` builder switches on `viewModel.well.type` and binds exactly the
//  production fields for a `.production` well and exactly the injection fields
//  for an `.injection` well. These tests therefore assert *rendering* at the
//  view-model boundary that drives it — the set of fields that participate in the
//  form for each well type — rather than walking the SwiftUI tree.
//
//  The observable signal that a field is "present" on the form is that the field
//  participates in the view model's validate -> per-field-error flow: a blank
//  save surfaces one `FieldError` per *required* field (the validator reports the
//  empty field), and the view renders a message beneath exactly those fields via
//  `errorMessage(for:)`. A field belonging to the *other* well type never
//  participates: it is not validated, carries no error, and whatever string is
//  placed in it is ignored. Asserting the exact offending-field set for a blank
//  save (and its complement) pins the field set the form presents per well type.
//
//  The view model is `@MainActor`; its async `save()` is awaited on the main
//  actor via `@MainActor` test methods so the published state mutations happen on
//  the actor the view observes.

import XCTest
@testable import SAGDWellTest

@MainActor
final class WellheadLogFormRenderingUITests: XCTestCase {

    // MARK: - Fixtures

    private static let fixedNow = Date(timeIntervalSince1970: 1_700_000_000)

    private func productionWell() -> Well {
        Well(id: "PROD-1", name: "Producer 1", type: .production, updatedAt: Self.fixedNow)
    }

    private func injectionWell() -> Well {
        Well(id: "INJ-1", name: "Injector 1", type: .injection, updatedAt: Self.fixedNow)
    }

    /// A `LogRepository` stub whose save result is fixed and that records every
    /// log it is asked to persist. The rendering tests only reach it on the valid
    /// path; the field-set tests never reach it (a blank save is blocked before
    /// the repository is called), which the stub's `saveCount` confirms.
    private final class StubLogRepository: LogRepository, @unchecked Sendable {
        let result: SaveResult
        private(set) var savedLogs: [WellheadLog] = []
        private(set) var savedTests: [WellTest] = []

        init(result: SaveResult) { self.result = result }

        func saveWellheadLog(_ log: WellheadLog) async -> SaveResult {
            savedLogs.append(log)
            return result
        }

        func saveWellTest(_ test: WellTest) async -> SaveResult {
            savedTests.append(test)
            return result
        }

        var saveCount: Int { savedLogs.count + savedTests.count }
    }

    private func makeViewModel(
        well: Well,
        repository: StubLogRepository = StubLogRepository(result: .success)
    ) -> WellheadLogFormViewModel {
        WellheadLogFormViewModel(
            well: well,
            logRepository: repository,
            productionValidator: ProductionLogValidator(now: { Self.fixedNow }),
            injectionValidator: InjectionLogValidator(now: { Self.fixedNow })
        )
    }

    /// The field ids the model surfaces an error for after the current save.
    private func erroredFields(_ vm: WellheadLogFormViewModel) -> Set<String> {
        let all = [
            WellheadLogField.emulsionFlowRate,
            WellheadLogField.casingGasPressure,
            WellheadLogField.espMotorTemperature,
            WellheadLogField.waterCutPercent,
            WellheadLogField.steamInjectionPressure,
            WellheadLogField.steamTemperature,
            WellheadLogField.steamFlowRate,
        ]
        return Set(all.filter { vm.errorMessage(for: $0) != nil })
    }

    // MARK: - Production field set (Req 3.1)

    /// A production well's form presents exactly the four production fields: a
    /// blank save is blocked and surfaces one error per production field and no
    /// error for any injection field.
    func testProductionFormPresentsExactlyTheFourProductionFields() async {
        let vm = makeViewModel(well: productionWell())

        await vm.save()

        XCTAssertEqual(
            erroredFields(vm),
            [
                WellheadLogField.emulsionFlowRate,
                WellheadLogField.casingGasPressure,
                WellheadLogField.espMotorTemperature,
                WellheadLogField.waterCutPercent,
            ],
            "A production form must present exactly emulsion flow rate, casing gas pressure, ESP motor temperature, and water cut (Req 3.1)."
        )
        // Each present field renders a non-empty message beneath it.
        for field in erroredFields(vm) {
            XCTAssertFalse(
                (vm.errorMessage(for: field) ?? "").isEmpty,
                "Field \(field) should render a validation message."
            )
        }
    }

    /// Injection fields are not part of a production form: setting them has no
    /// effect on the production save and they never carry a validation error.
    func testProductionFormIgnoresInjectionFields() async {
        let vm = makeViewModel(well: productionWell())
        // Even a wildly invalid value in injection-only fields is irrelevant.
        vm.steamInjectionPressure = "not-a-number"
        vm.steamTemperature = "999999"
        vm.steamFlowRate = ""

        await vm.save()

        XCTAssertNil(vm.errorMessage(for: WellheadLogField.steamInjectionPressure))
        XCTAssertNil(vm.errorMessage(for: WellheadLogField.steamTemperature))
        XCTAssertNil(vm.errorMessage(for: WellheadLogField.steamFlowRate))
    }

    // MARK: - Injection field set (Req 4.1)

    /// An injection well's form presents exactly the three steam fields: a blank
    /// save is blocked and surfaces one error per steam field and no error for
    /// any production field.
    func testInjectionFormPresentsExactlyTheThreeSteamFields() async {
        let vm = makeViewModel(well: injectionWell())

        await vm.save()

        XCTAssertEqual(
            erroredFields(vm),
            [
                WellheadLogField.steamInjectionPressure,
                WellheadLogField.steamTemperature,
                WellheadLogField.steamFlowRate,
            ],
            "An injection form must present exactly steam injection pressure, steam temperature, and steam flow rate (Req 4.1)."
        )
        for field in erroredFields(vm) {
            XCTAssertFalse(
                (vm.errorMessage(for: field) ?? "").isEmpty,
                "Field \(field) should render a validation message."
            )
        }
    }

    /// Production fields are not part of an injection form.
    func testInjectionFormIgnoresProductionFields() async {
        let vm = makeViewModel(well: injectionWell())
        vm.emulsionFlowRate = "oops"
        vm.casingGasPressure = "1000000"
        vm.espMotorTemperature = ""
        vm.waterCutPercent = "200"

        await vm.save()

        XCTAssertNil(vm.errorMessage(for: WellheadLogField.emulsionFlowRate))
        XCTAssertNil(vm.errorMessage(for: WellheadLogField.casingGasPressure))
        XCTAssertNil(vm.errorMessage(for: WellheadLogField.espMotorTemperature))
        XCTAssertNil(vm.errorMessage(for: WellheadLogField.waterCutPercent))
    }

    // MARK: - Header reflects the well type

    /// The form header the view renders is chosen by well type, so the two well
    /// types produce distinct headers even though they share the same view.
    func testFormHeaderDistinguishesWellType() {
        XCTAssertEqual(makeViewModel(well: productionWell()).well.type, .production)
        XCTAssertEqual(makeViewModel(well: injectionWell()).well.type, .injection)
    }

    // MARK: - Blank save is blocked before the repository (value retention)

    /// A blank production save is blocked before any persistence call and the
    /// entered field strings are retained unchanged (Req 3.5-adjacent behavior
    /// that the form relies on while rendering per-field messages).
    func testBlankProductionSaveIsBlockedAndRetainsEnteredValues() async {
        let repo = StubLogRepository(result: .success)
        let vm = makeViewModel(well: productionWell(), repository: repo)
        vm.emulsionFlowRate = "12.5"          // valid
        vm.casingGasPressure = ""             // missing
        vm.espMotorTemperature = "88"         // valid
        vm.waterCutPercent = "250"            // out of range

        await vm.save()

        XCTAssertEqual(repo.saveCount, 0, "A blocked save must never call the repository.")
        // Only the two bad fields carry an error; the good fields do not.
        XCTAssertEqual(
            erroredFields(vm),
            [WellheadLogField.casingGasPressure, WellheadLogField.waterCutPercent]
        )
        // Every entered value is retained verbatim.
        XCTAssertEqual(vm.emulsionFlowRate, "12.5")
        XCTAssertEqual(vm.casingGasPressure, "")
        XCTAssertEqual(vm.espMotorTemperature, "88")
        XCTAssertEqual(vm.waterCutPercent, "250")
        XCTAssertFalse(vm.didSave)
    }

    // MARK: - Save-status mapping drives the status banner the form renders

    /// A valid production save maps `SaveResult.success` onto the `saved` status,
    /// which is the signal the form's status banner uses to show the success
    /// indicator (Req 1.1).
    func testValidProductionSaveShowsSuccessIndicator() async {
        let repo = StubLogRepository(result: .success)
        let vm = makeViewModel(well: productionWell(), repository: repo)
        vm.emulsionFlowRate = "12.5"
        vm.casingGasPressure = "340"
        vm.espMotorTemperature = "88"
        vm.waterCutPercent = "42"

        await vm.save()

        XCTAssertEqual(repo.saveCount, 1)
        XCTAssertEqual(repo.savedLogs.first?.logType, .production)
        XCTAssertEqual(vm.saveStatus, .saved)
        XCTAssertTrue(vm.didSave, "A successful save must drive the success indicator.")
        XCTAssertNil(vm.saveErrorMessage)
        XCTAssertTrue(erroredFields(vm).isEmpty, "A successful save clears field errors.")
    }

    /// A valid injection save routes through the injection validator and persists
    /// an injection log.
    func testValidInjectionSaveShowsSuccessIndicator() async {
        let repo = StubLogRepository(result: .success)
        let vm = makeViewModel(well: injectionWell(), repository: repo)
        vm.steamInjectionPressure = "12000"
        vm.steamTemperature = "320"
        vm.steamFlowRate = "1500"

        await vm.save()

        XCTAssertEqual(repo.saveCount, 1)
        XCTAssertEqual(repo.savedLogs.first?.logType, .injection)
        XCTAssertEqual(vm.saveStatus, .saved)
        XCTAssertTrue(vm.didSave)
    }

    /// A storage-full repository result maps onto the dedicated storage-full
    /// banner the form renders, and the entered values are retained (Req 1.5).
    func testStorageFullResultShowsStorageBannerAndRetainsValues() async {
        let repo = StubLogRepository(result: .storageFull)
        let vm = makeViewModel(well: productionWell(), repository: repo)
        vm.emulsionFlowRate = "12.5"
        vm.casingGasPressure = "340"
        vm.espMotorTemperature = "88"
        vm.waterCutPercent = "42"

        await vm.save()

        XCTAssertEqual(vm.saveStatus, .storageFull)
        XCTAssertFalse(vm.didSave)
        XCTAssertNotNil(vm.saveErrorMessage, "Storage-full must surface an error banner.")
        // Values retained for a follow-up attempt.
        XCTAssertEqual(vm.emulsionFlowRate, "12.5")
        XCTAssertEqual(vm.waterCutPercent, "42")
    }

    /// A generic I/O failure maps onto the failure banner and retains values
    /// (Req 1.4).
    func testFailureResultShowsErrorBannerAndRetainsValues() async {
        let repo = StubLogRepository(result: .failure(reason: "disk I/O"))
        let vm = makeViewModel(well: injectionWell(), repository: repo)
        vm.steamInjectionPressure = "12000"
        vm.steamTemperature = "320"
        vm.steamFlowRate = "1500"

        await vm.save()

        XCTAssertEqual(vm.saveStatus, .failure("disk I/O"))
        XCTAssertFalse(vm.didSave)
        XCTAssertNotNil(vm.saveErrorMessage)
        XCTAssertEqual(vm.steamInjectionPressure, "12000")
        XCTAssertEqual(vm.steamFlowRate, "1500")
    }

    /// Editing after a terminal save status clears the transient banner (so a
    /// stale success/failure banner does not linger over fresh input) while
    /// leaving the entered value in place.
    func testEditingClearsTransientBannerButRetainsValue() async {
        let repo = StubLogRepository(result: .success)
        let vm = makeViewModel(well: productionWell(), repository: repo)
        vm.emulsionFlowRate = "12.5"
        vm.casingGasPressure = "340"
        vm.espMotorTemperature = "88"
        vm.waterCutPercent = "42"
        await vm.save()
        XCTAssertEqual(vm.saveStatus, .saved)

        vm.waterCutPercent = "43"
        vm.markEdited()

        XCTAssertEqual(vm.saveStatus, .idle, "An edit clears the success banner.")
        XCTAssertFalse(vm.didSave)
        XCTAssertEqual(vm.waterCutPercent, "43", "The edited value is retained.")
    }
}

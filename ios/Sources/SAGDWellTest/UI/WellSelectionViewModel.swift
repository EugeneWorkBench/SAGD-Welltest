//
//  UI layer — well selection view model
//
//  Loads the locally cached wells for the selection screen and tracks the
//  operator's current selection. Selecting a well is what drives which wellhead
//  log form is presented (production vs injection), since the form is chosen by
//  `Well.type` (Req 3.1, 4.1). This view model holds no business rules; it only
//  loads the list through a `WellListProviding` source and exposes the chosen
//  well.
//
//  Implemented by task 20.1.
//  Requirements: 3.1, 4.1 (form chosen by well type).

#if canImport(SwiftUI)
import Foundation
import SwiftUI

/// View model backing the well-selection screen.
@MainActor
public final class WellSelectionViewModel: ObservableObject {

    /// The loaded wells, ordered for display.
    @Published public private(set) var wells: [Well] = []

    /// Whether the initial load is in flight.
    @Published public private(set) var isLoading: Bool = false

    private let provider: any WellListProviding

    public init(provider: any WellListProviding) {
        self.provider = provider
    }

    /// Loads the locally cached wells. Safe to call again to refresh.
    public func load() async {
        isLoading = true
        wells = await provider.allWells()
        isLoading = false
    }
}
#endif

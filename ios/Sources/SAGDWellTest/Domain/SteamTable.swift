//
//  Steam table
//
//  The `SteamTable` is a monotonic reference dataset of
//  (saturation pressure -> saturation temperature) pairs bundled with the app.
//  Saturation temperature increases monotonically with saturation pressure,
//  and `saturationTemperature(pressure:)` preserves that relationship exactly
//  by linear interpolation within the bracketing pair (design "Subcool
//  Estimator / Steam-table lookup and interpolation").
//
//  Pressures are in kPa (absolute); temperatures are in degrees Celsius. The
//  bundled table covers a realistic SAGD casing-pressure operating range
//  (roughly atmospheric up to ~6 MPa) using water/steam saturation data. A
//  pressure outside `[minPressure, maxPressure]` is "out of range": the lookup
//  returns `nil` and the estimator rejects the submission (Req 5.4).
//
//  This type is pure value logic with no I/O and no dependency on the
//  persistence engine, networking, or UI. Implemented by task 8.1.

import Foundation

/// A monotonic saturation steam table and the bracket-lookup + linear
/// interpolation used to derive a saturation temperature for a pressure.
///
/// The table is an ascending-by-pressure list of `(pressure, temperature)`
/// points. Both pressure and temperature are strictly increasing across the
/// points, which is what makes the interpolated `saturationTemperature(_:)`
/// monotonic in pressure (a property asserted by the design's correctness
/// properties).
public struct SteamTable: Sendable, Equatable {

    /// A single saturation data point: saturation `pressure` (kPa) and the
    /// corresponding saturation `temperature` (°C).
    public struct Point: Sendable, Equatable {
        /// Saturation pressure in kPa (absolute).
        public var pressure: Double
        /// Saturation temperature in °C.
        public var temperature: Double

        public init(pressure: Double, temperature: Double) {
            self.pressure = pressure
            self.temperature = temperature
        }
    }

    /// The table's points, ordered by ascending pressure.
    public let points: [Point]

    // MARK: - Construction

    /// Creates a steam table from an explicit list of points.
    ///
    /// The points MUST be sorted by ascending pressure and be strictly
    /// monotonic in both pressure and temperature; this initializer asserts
    /// those invariants in debug builds because the interpolation and the
    /// monotonicity guarantee both depend on them. The table must contain at
    /// least two points so a bracket can always be formed within the domain.
    ///
    /// - Parameter points: Saturation points ordered by ascending pressure.
    public init(points: [Point]) {
        assert(points.count >= 2, "A steam table needs at least two points to bracket a pressure.")
        assert(
            zip(points, points.dropFirst()).allSatisfy {
                $0.pressure < $1.pressure && $0.temperature < $1.temperature
            },
            "Steam-table points must be strictly increasing in both pressure and temperature."
        )
        self.points = points
    }

    // MARK: - Domain

    /// The lowest pressure (kPa) in the table's domain.
    public var minPressure: Double {
        // `points` is non-empty by construction (>= 2 points).
        points.first!.pressure
    }

    /// The highest pressure (kPa) in the table's domain.
    public var maxPressure: Double {
        points.last!.pressure
    }

    // MARK: - Lookup

    /// The bracketing pair `(P0, T0)` / `(P1, T1)` such that
    /// `P0 <= pressure <= P1`, or `nil` when `pressure` is outside the table's
    /// domain.
    ///
    /// When `pressure` exactly equals a table point, the returned bracket
    /// collapses onto that point (`P0 == P1`), which the interpolation treats
    /// as an exact hit.
    ///
    /// - Parameter pressure: The pressure to bracket (kPa).
    /// - Returns: The bracketing pair, or `nil` if out of domain.
    public func bracket(_ pressure: Double) -> (lower: Point, upper: Point)? {
        guard pressure >= minPressure, pressure <= maxPressure else {
            return nil
        }

        // Linear scan is adequate for the bundled table's size and keeps the
        // lookup trivially correct; the table is small and fixed.
        for index in 0..<(points.count - 1) {
            let lower = points[index]
            let upper = points[index + 1]
            if pressure >= lower.pressure && pressure <= upper.pressure {
                return (lower, upper)
            }
        }

        // Unreachable given the domain guard above, but keeps the function
        // total rather than relying on the loop's structure.
        return nil
    }

    /// The saturation temperature (°C) for `pressure`, or `nil` when `pressure`
    /// is outside the table's domain.
    ///
    /// Within the domain, the value is the linear interpolation of the
    /// bracketing pair:
    /// `Tsat = T0 + (P - P0) * (T1 - T0) / (P1 - P0)`.
    /// An exact table hit (`P0 == P1`) returns that point's temperature
    /// directly, avoiding a divide-by-zero.
    ///
    /// - Parameter pressure: The pressure to look up (kPa).
    /// - Returns: The interpolated saturation temperature, or `nil` if out of
    ///   domain.
    public func saturationTemperature(pressure: Double) -> Double? {
        guard let (lower, upper) = bracket(pressure) else {
            return nil
        }
        if upper.pressure == lower.pressure {
            return lower.temperature
        }
        return lower.temperature
            + (pressure - lower.pressure)
            * (upper.temperature - lower.temperature)
            / (upper.pressure - lower.pressure)
    }

    // MARK: - Bundled dataset

    /// The monotonic steam table bundled with the app.
    ///
    /// Water/steam saturation pairs over a realistic SAGD casing-pressure range
    /// — from roughly atmospheric pressure (101.3 kPa, 100 °C) up to ~6 MPa
    /// (6000 kPa, ~275.6 °C). The points are dense enough over the operating
    /// range that linear interpolation error is negligible for field use, and
    /// both columns increase monotonically so the derived saturation
    /// temperature is monotonic in pressure.
    ///
    /// Values are standard water saturation data (IAPWS-aligned), rounded to a
    /// precision appropriate for field estimation.
    public static let bundled = SteamTable(points: [
        Point(pressure: 101.3, temperature: 100.0),
        Point(pressure: 150.0, temperature: 111.4),
        Point(pressure: 200.0, temperature: 120.2),
        Point(pressure: 250.0, temperature: 127.4),
        Point(pressure: 300.0, temperature: 133.5),
        Point(pressure: 400.0, temperature: 143.6),
        Point(pressure: 500.0, temperature: 151.8),
        Point(pressure: 600.0, temperature: 158.8),
        Point(pressure: 700.0, temperature: 165.0),
        Point(pressure: 800.0, temperature: 170.4),
        Point(pressure: 900.0, temperature: 175.4),
        Point(pressure: 1000.0, temperature: 179.9),
        Point(pressure: 1200.0, temperature: 188.0),
        Point(pressure: 1400.0, temperature: 195.0),
        Point(pressure: 1600.0, temperature: 201.4),
        Point(pressure: 1800.0, temperature: 207.1),
        Point(pressure: 2000.0, temperature: 212.4),
        Point(pressure: 2200.0, temperature: 217.2),
        Point(pressure: 2400.0, temperature: 221.8),
        Point(pressure: 2600.0, temperature: 226.0),
        Point(pressure: 2800.0, temperature: 230.0),
        Point(pressure: 3000.0, temperature: 233.9),
        Point(pressure: 3200.0, temperature: 237.5),
        Point(pressure: 3400.0, temperature: 240.9),
        Point(pressure: 3600.0, temperature: 244.2),
        Point(pressure: 3800.0, temperature: 247.3),
        Point(pressure: 4000.0, temperature: 250.4),
        Point(pressure: 4400.0, temperature: 256.1),
        Point(pressure: 4800.0, temperature: 261.4),
        Point(pressure: 5000.0, temperature: 263.9),
        Point(pressure: 5500.0, temperature: 269.9),
        Point(pressure: 6000.0, temperature: 275.6)
    ])
}

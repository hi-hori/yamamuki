import XCTest
@testable import YamamukiCore

final class BundledPeakIndexTests: XCTestCase {
    private func encoded(_ mountains: [Mountain]) throws -> Data {
        try JSONEncoder().encode(mountains)
    }

    func testNamesMissingElevationAndRadius() throws {
        let mountain = Mountain(osmId: 1, name: "山,\"峰\"\n北", latitude: 35, longitude: 139, elevationM: nil)
        let index = try BundledPeakIndex(data: encoded([mountain]), expectedCount: 1)
        XCTAssertEqual(index.mountains, [mountain])
        XCTAssertEqual(index.nearby(latitude: 35, longitude: 139, radiusKm: 1), [mountain])
        XCTAssertTrue(index.nearby(latitude: 0, longitude: 0, radiusKm: 1).isEmpty)
    }

    func testRejectsDuplicateInvalidAndWrongCount() throws {
        let mountain = Mountain(osmId: 1, name: "山", latitude: 35, longitude: 139, elevationM: 1)
        XCTAssertThrowsError(try BundledPeakIndex(data: encoded([mountain, mountain]), expectedCount: 2))
        XCTAssertThrowsError(try BundledPeakIndex(data: encoded([mountain]), expectedCount: 2))
        let invalid = Mountain(osmId: 2, name: "山", latitude: 91, longitude: 139, elevationM: nil)
        XCTAssertThrowsError(try BundledPeakIndex(data: encoded([invalid]), expectedCount: 1))
    }

    func testGeneratedJapanPack() throws {
        let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let folder = ios.appendingPathComponent("Generated/Peaks")
        guard FileManager.default.fileExists(atPath: folder.appendingPathComponent("peaks.json").path) else {
            throw XCTSkip("Run python3 ios/prepare-peaks.py to validate the bundled Japan data")
        }
        struct Info: Decodable { let count: Int }
        let info = try JSONDecoder().decode(Info.self, from: Data(contentsOf: folder.appendingPathComponent("info.json")))
        let index = try BundledPeakIndex(data: Data(contentsOf: folder.appendingPathComponent("peaks.json")), expectedCount: info.count)
        XCTAssertGreaterThan(index.mountains.count, 10000)
        XCTAssertTrue(index.nearby(latitude: 35.3606, longitude: 138.7274, radiusKm: 2).contains { $0.name.contains("富士") })
    }
}

import CryptoKit
import Foundation
import YamamukiCore

struct BundledPeakInfo: Decodable, Sendable {
    let count: Int
    let osmDate: String
    let inputDate: String
    let bytes: Int64
    let sourceSHA256: String
    let peaksSHA256: String
}

actor BundledPeakStore {
    private var index: BundledPeakIndex?
    private var metadata: BundledPeakInfo?

    private func initialize() throws {
        guard index == nil else { return }
        guard let folder = Bundle.main.resourceURL?.appendingPathComponent("Peaks") else {
            throw CocoaError(.fileNoSuchFile)
        }
        let info = try JSONDecoder().decode(BundledPeakInfo.self, from: Data(contentsOf: folder.appendingPathComponent("info.json")))
        let data = try Data(contentsOf: folder.appendingPathComponent("peaks.json"))
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard hash == info.peaksSHA256 else { throw CocoaError(.fileReadCorruptFile) }
        index = try BundledPeakIndex(data: data, expectedCount: info.count)
        metadata = info
    }

    func info() throws -> BundledPeakInfo {
        try initialize()
        return metadata!
    }

    func nearby(latitude: Double, longitude: Double, radiusKm: Double) throws -> [Mountain] {
        try initialize()
        return index!.nearby(latitude: latitude, longitude: longitude, radiusKm: radiusKm)
    }
}

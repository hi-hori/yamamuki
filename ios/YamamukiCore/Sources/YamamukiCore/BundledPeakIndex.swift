import Foundation

/// 内蔵データは起動後に一度だけ解析する。範囲抽出は呼び出し側のactor上で行う。
public struct BundledPeakIndex: Sendable {
    public let mountains: [Mountain]

    public init(data: Data, expectedCount: Int) throws {
        let decoded = try JSONDecoder().decode([Mountain].self, from: data)
        guard decoded.count == expectedCount,
              Set(decoded.map(\.osmId)).count == decoded.count,
              decoded.allSatisfy({ !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                  (-90...90).contains($0.latitude) && (-180...180).contains($0.longitude) &&
                  ($0.elevationM?.isFinite ?? true) }) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        mountains = decoded
    }

    public func nearby(latitude: Double, longitude: Double, radiusKm: Double) -> [Mountain] {
        mountains.filter { GeoMath.distanceKm(latitude, longitude, $0.latitude, $0.longitude) <= radiusKm }
    }
}

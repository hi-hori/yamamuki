import Foundation

public struct TerrainKey: Hashable, Sendable {
    public let zoom: Int
    public let x: Int
    public let y: Int
    public init(zoom: Int, x: Int, y: Int) { self.zoom = zoom; self.x = x; self.y = y }
    public var path: String { "\(zoom)/\(x)/\(y).webp" }
}

public enum TerrainGeometry {
    public static func zoom(_ rangeKm: Double) -> Int {
        rangeKm <= 16 ? 12 : (rangeKm <= 50 ? 11 : 10)
    }

    public static func key(latitude: Double, longitude: Double, zoom: Int) -> TerrainKey {
        let n = Double(1 << zoom)
        let latitude = min(85, max(-85, latitude))
        let x = Int(floor((longitude + 180) / 360 * n))
        let y = Int(floor((1 - asinh(tan(radians(latitude))) / .pi) / 2 * n))
        return TerrainKey(zoom: zoom, x: min(Int(n)-1, max(0,x)), y: min(Int(n)-1, max(0,y)))
    }

    public static func covering(latitude: Double, longitude: Double, radiusKm: Double, zoom: Int) -> [TerrainKey] {
        let box = BoundingBox.around(latitude, longitude, radiusKm: radiusKm)
        let nw = key(latitude: box.north, longitude: box.west, zoom: zoom)
        let se = key(latitude: box.south, longitude: box.east, zoom: zoom)
        return (nw.y...se.y).flatMap { y in
            (nw.x...se.x).map { TerrainKey(zoom: zoom, x: $0, y: y) }
        }
    }

    /// Row-major mesh, in the same great-circle projection as mountain icons.
    public static func mesh(_ key: TerrainKey, latitude: Double, longitude: Double, divisions: Int = 4) -> [PlanOffset] {
        precondition(divisions > 0)
        let n = Double(1 << key.zoom)
        return (0...divisions).flatMap { j in (0...divisions).map { i in
            let lon = (Double(key.x) + Double(i)/Double(divisions)) / n * 360 - 180
            let lat = degrees(atan(sinh(.pi * (1 - 2 * (Double(key.y) + Double(j)/Double(divisions)) / n))))
            return DialGeometry.project(distanceKm: GeoMath.distanceKm(latitude,longitude,lat,lon),
                bearingDeg: GeoMath.bearingDeg(latitude,longitude,lat,lon), headingDeg: 0)
        } }
    }
}

import Foundation

/// Cached path bounds, in east/right and south/down kilometre coordinates.
public struct RiverBounds: Sendable {
    public let left: Double, top: Double, right: Double, bottom: Double
    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left; self.top = top; self.right = right; self.bottom = bottom
    }
    public func visible(originX: Double, originY: Double, scale: Double, heading: Double,
                        width: Double, chartTop: Double, chartBottom: Double, padding: Double) -> Bool {
        let angle = heading * .pi / 180
        let c = cos(angle), s = sin(angle)
        let x = (left+right)/2, y = (top+bottom)/2
        let dx = (right-left)/2, dy = (bottom-top)/2
        let cx = originX + (c*x+s*y)*scale, cy = originY + (-s*x+c*y)*scale
        let rx = (abs(c)*dx+abs(s)*dy)*scale + padding
        let ry = (abs(s)*dx+abs(c)*dy)*scale + padding
        return cx+rx >= 0 && cx-rx <= width && cy+ry >= chartTop && cy-ry <= chartBottom
    }
}

public enum RiverGeometry {
    public enum DecodeError: Error { case invalidTile }

    public static func bucket(_ line: [PlanOffset], divisions: Int) -> Int {
        let a = line.first!, b = line.last!
        let x = min(divisions-1,max(0,Int((a.x+b.x)/2 * Double(divisions))))
        let y = min(divisions-1,max(0,Int((a.y+b.y)/2 * Double(divisions))))
        return y*divisions+x
    }

    /// RIV1 little-endian tile, normalized to 0...1 Mercator coordinates.
    public static func decode(_ data: Data) throws -> [[PlanOffset]] {
        let bytes = [UInt8](data)
        guard bytes.count >= 8, Array(bytes.prefix(4)) == [82,73,86,49] else { throw DecodeError.invalidTile }
        var offset = 4
        func integer(_ size: Int) throws -> Int {
            guard offset + size <= bytes.count else { throw DecodeError.invalidTile }
            var value = 0
            for i in 0..<size { value |= Int(bytes[offset+i]) << (8*i) }
            offset += size
            return value
        }
        let count = try integer(4)
        guard count <= (bytes.count-offset)/12 else { throw DecodeError.invalidTile }
        var lines: [[PlanOffset]] = []
        for _ in 0..<count {
            let count = try integer(4)
            guard count >= 2, count <= (bytes.count-offset)/4 else { throw DecodeError.invalidTile }
            var line: [PlanOffset] = []
            for _ in 0..<count {
                let x = try integer(2), y = try integer(2)
                guard x <= 4096, y <= 4096 else { throw DecodeError.invalidTile }
                line.append(PlanOffset(x: Double(x)/4096, y: Double(y)/4096))
            }
            lines.append(line)
        }
        guard offset == bytes.count else { throw DecodeError.invalidTile }
        return lines
    }

    public static func project(_ key: TerrainKey, point: PlanOffset, latitude: Double, longitude: Double) -> PlanOffset {
        let n = Double(1 << key.zoom)
        let lon = (Double(key.x) + point.x) / n * 360 - 180
        let lat = atan(sinh(.pi * (1 - 2 * (Double(key.y) + point.y) / n))) * 180 / .pi
        return DialGeometry.project(distanceKm: GeoMath.distanceKm(latitude,longitude,lat,lon),
            bearingDeg: GeoMath.bearingDeg(latitude,longitude,lat,lon), headingDeg: 0)
    }
}

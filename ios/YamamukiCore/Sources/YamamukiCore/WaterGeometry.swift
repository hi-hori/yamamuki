import Foundation

public struct WaterPolygon: Sendable {
    public let river: Bool
    public let rings: [[PlanOffset]]
    public init(river: Bool, rings: [[PlanOffset]]) { self.river = river; self.rings = rings }
}

public enum WaterGeometry {
    public static func decode(_ data: Data) throws -> [WaterPolygon] {
        let bytes = [UInt8](data)
        guard bytes.count >= 8, Array(bytes.prefix(4)) == [87,65,84,49] else { throw RiverGeometry.DecodeError.invalidTile }
        var offset = 4
        func integer(_ size: Int) throws -> Int {
            guard offset + size <= bytes.count else { throw RiverGeometry.DecodeError.invalidTile }
            var value = 0
            for i in 0..<size { value |= Int(bytes[offset+i]) << (i*8) }
            offset += size
            return value
        }
        let count = try integer(4)
        guard count <= (bytes.count-offset)/24 else { throw RiverGeometry.DecodeError.invalidTile }
        var polygons: [WaterPolygon] = []
        for _ in 0..<count {
            let kind = try integer(4), ringCount = try integer(4)
            guard kind <= 1, ringCount >= 1, ringCount <= (bytes.count-offset)/16 else { throw RiverGeometry.DecodeError.invalidTile }
            var rings: [[PlanOffset]] = []
            for _ in 0..<ringCount {
                let points = try integer(4)
                guard points >= 3, points <= (bytes.count-offset)/4 else { throw RiverGeometry.DecodeError.invalidTile }
                var ring: [PlanOffset] = []
                for _ in 0..<points {
                    let x = try integer(2), y = try integer(2)
                    guard x <= 4096, y <= 4096 else { throw RiverGeometry.DecodeError.invalidTile }
                    ring.append(PlanOffset(x:Double(x)/4096,y:Double(y)/4096))
                }
                rings.append(ring)
            }
            polygons.append(WaterPolygon(river:kind == 1,rings:rings))
        }
        guard offset == bytes.count else { throw RiverGeometry.DecodeError.invalidTile }
        return polygons
    }
}

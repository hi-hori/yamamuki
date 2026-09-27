import Foundation
import CoreGraphics
import YamamukiCore

struct RiverBatch {
    let path: CGPath
    let bounds: RiverBounds
}

struct WaterBatch {
    let path: CGPath
    let bounds: RiverBounds
    let river: Bool
    let sea: Bool
}

struct RiverFrame: @unchecked Sendable {
    let water: [WaterBatch]
    let batches: [RiverBatch]
    let latitude: Double
    let longitude: Double
    let widthKm: Double
}

private final class RiverTile: NSObject {
    let lines: [[PlanOffset]]
    init(_ lines: [[PlanOffset]]) { self.lines = lines }
}

private final class WaterTile: NSObject {
    let polygons: [WaterPolygon]
    init(_ polygons: [WaterPolygon]) { self.polygons = polygons }
}

actor RiverStore {
    private let root = Bundle.main.url(forResource: "Terrain", withExtension: nil)
    private let cache = NSCache<NSString, RiverTile>()
    private let waterCache = NSCache<NSString, WaterTile>()

    init() { cache.totalCostLimit = 8 * 1024 * 1024; waterCache.totalCostLimit = 8 * 1024 * 1024 }

    func render(latitude: Double, longitude: Double, viewportLatitude: Double,
                viewportLongitude: Double, rangeKm: Double, showRivers: Bool, showLakes: Bool) throws -> RiverFrame {
        guard let root else { throw TerrainError.missingBundle }
        var batches: [RiverBatch] = []
        var water: [WaterBatch] = []
        let keys = TerrainGeometry.covering(latitude: viewportLatitude, longitude: viewportLongitude,
            radiusKm: rangeKm * 1.4, zoom: TerrainGeometry.zoom(rangeKm))
        let covered = keys.contains { key in
            FileManager.default.fileExists(atPath: root.appendingPathComponent("sea/\(key.zoom)/\(key.x)/\(key.y).wat").path)
        }
        for key in keys {
            try Task.checkCancellation()
            for layer in ["sea", "water"] {
                let sea = layer == "sea"
                let waterName = "\(layer)/\(key.zoom)/\(key.x)/\(key.y).wat"
                let waterURL = root.appendingPathComponent(waterName)
                let exists = FileManager.default.fileExists(atPath: waterURL.path)
                if exists || (sea && covered) {
                    let tile: WaterTile
                    if !exists {
                        tile = WaterTile([WaterPolygon(river: true, rings: [[PlanOffset(x: 0, y: 0),
                            PlanOffset(x: 1, y: 0), PlanOffset(x: 1, y: 1), PlanOffset(x: 0, y: 1)]])])
                    } else if let hit = waterCache.object(forKey: waterName as NSString) { tile = hit }
                    else {
                        tile = WaterTile(try WaterGeometry.decode(Data(contentsOf: waterURL)))
                        let count = tile.polygons.reduce(0) { $0 + $1.rings.reduce(0) { $0 + $1.count } }
                        waterCache.setObject(tile, forKey: waterName as NSString, cost: 64 + count * 24)
                    }
                    var waterPaths: [Bool: CGMutablePath] = [:]
                    for polygon in tile.polygons {
                        if !sea && (polygon.river ? !showRivers : !showLakes) { continue }
                        try Task.checkCancellation()
                        let path = waterPaths[polygon.river] ?? CGMutablePath()
                        waterPaths[polygon.river] = path
                        for ring in polygon.rings {
                            for (i, point) in ring.enumerated() {
                                let p = RiverGeometry.project(key, point: point, latitude: latitude, longitude: longitude)
                                let screen = CGPoint(x: p.x, y: -p.y)
                                if i == 0 { path.move(to: screen) } else { path.addLine(to: screen) }
                            }
                            path.closeSubpath()
                        }
                    }
                    for (river, path) in waterPaths {
                        let bounds = path.boundingBoxOfPath
                        water.append(WaterBatch(path: path.copy()!, bounds: RiverBounds(left: Double(bounds.minX),
                            top: Double(bounds.minY), right: Double(bounds.maxX), bottom: Double(bounds.maxY)), river: river, sea: sea))
                    }
                }
            }
            if !showRivers { continue }
            let name = "rivers/\(key.zoom)/\(key.x)/\(key.y).riv"
            let url = root.appendingPathComponent(name)
            let tile: RiverTile
            if let hit = cache.object(forKey: name as NSString) { tile = hit }
            else {
                guard FileManager.default.fileExists(atPath: url.path) else { continue }
                tile = RiverTile(try RiverGeometry.decode(Data(contentsOf: url)))
                cache.setObject(tile, forKey: name as NSString,
                    cost: 64 + tile.lines.reduce(0) { $0 + 32 + $1.count * 16 })
            }
            var paths: [Int: CGMutablePath] = [:]
            let divisions = key.zoom == 12 ? 4 : 2
            for line in tile.lines {
                try Task.checkCancellation()
                let bucket = RiverGeometry.bucket(line, divisions: divisions)
                let path = paths[bucket] ?? CGMutablePath()
                paths[bucket] = path
                for (i, point) in line.enumerated() {
                    let p = RiverGeometry.project(key, point: point, latitude: latitude, longitude: longitude)
                    let screen = CGPoint(x: p.x, y: -p.y)
                    if i == 0 { path.move(to: screen) } else { path.addLine(to: screen) }
                }
            }
            for key in paths.keys.sorted() {
                let path = paths[key]!
                let box = path.boundingBoxOfPath
                batches.append(RiverBatch(path: path.copy()!, bounds: RiverBounds(
                    left: Double(box.minX), top: Double(box.minY), right: Double(box.maxX), bottom: Double(box.maxY))))
            }
        }
        let widthKm = 40075.0166856 * cos(viewportLatitude * .pi / 180) / Double(1 << TerrainGeometry.zoom(rangeKm)) / 512 * 2.8
        return RiverFrame(water: water, batches: batches, latitude: latitude, longitude: longitude, widthKm: widthKm)
    }
}

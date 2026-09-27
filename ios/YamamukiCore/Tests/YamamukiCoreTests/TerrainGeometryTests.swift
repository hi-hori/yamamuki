import XCTest
@testable import YamamukiCore

final class TerrainGeometryTests: XCTestCase {
    func testTokyoTileAndEarlyZoomSwitching() {
        XCTAssertEqual(TerrainGeometry.key(latitude:35.696,longitude:139.814,zoom:12),
                       TerrainKey(zoom:12,x:3638,y:1612))
        XCTAssertEqual(TerrainGeometry.zoom(16),12)
        XCTAssertEqual(TerrainGeometry.zoom(16.01),11)
        XCTAssertEqual(TerrainGeometry.zoom(50),11)
        XCTAssertEqual(TerrainGeometry.zoom(50.01),10)
    }

    func testAdjacentMeshesHaveIdenticalBorders() {
        let left = TerrainGeometry.mesh(TerrainKey(zoom:12,x:3626,y:1617),latitude:35.36,longitude:138.73)
        let right = TerrainGeometry.mesh(TerrainKey(zoom:12,x:3627,y:1617),latitude:35.36,longitude:138.73)
        for row in 0...4 { XCTAssertEqual(left[row*5+4],right[row*5]) }
        XCTAssertLessThan(left[0].x,left[4].x)
        XCTAssertGreaterThan(left[0].y,left[20].y)
    }

    func testCoverageIncludesCenterAndAllBoxCorners() {
        let box = BoundingBox.around(35.696,139.814,radiusKm:22.4)
        let keys = Set(TerrainGeometry.covering(latitude:35.696,longitude:139.814,radiusKm:22.4,zoom:12))
        for lat in [box.south,35.696,box.north] {
            for lon in [box.west,139.814,box.east] {
                XCTAssertTrue(keys.contains(TerrainGeometry.key(latitude:lat,longitude:lon,zoom:12)))
            }
        }
    }
}

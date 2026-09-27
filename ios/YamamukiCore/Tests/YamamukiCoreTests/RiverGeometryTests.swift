import XCTest
@testable import YamamukiCore

final class RiverGeometryTests: XCTestCase {
    func testVisibilityRespectsRotationAndStrokeAtScreenEdge() {
        let bounds = RiverBounds(left:-0.1,top:-10,right:0.1,bottom:-9)
        func visible(_ heading: Double) -> Bool {
            bounds.visible(originX:50,originY:100,scale:10,heading:heading,
                width:100,chartTop:0,chartBottom:100,padding:0)
        }
        XCTAssertTrue(visible(0))
        XCTAssertFalse(visible(90))
        XCTAssertFalse(visible(180))
        let edge = RiverBounds(left:-5.2,top:-1,right:-5.1,bottom:0)
        XCTAssertFalse(edge.visible(originX:50,originY:50,scale:10,heading:0,width:100,chartTop:0,chartBottom:100,padding:0))
        XCTAssertTrue(edge.visible(originX:50,originY:50,scale:10,heading:0,width:100,chartTop:0,chartBottom:100,padding:2))
    }

    private let bytes: [UInt8] = [82,73,86,49,1,0,0,0,2,0,0,0,0,0,0,0,0,16,0,16]

    func testBuilderFormatMatchesTerrainProjection() throws {
        let line = try XCTUnwrap(RiverGeometry.decode(Data(bytes)).first)
        XCTAssertEqual(line,[PlanOffset(x:0,y:0),PlanOffset(x:1,y:1)])
        let key = TerrainGeometry.key(latitude:35.696,longitude:139.814,zoom:12)
        let mesh = TerrainGeometry.mesh(key,latitude:35.696,longitude:139.814)
        XCTAssertEqual(mesh.first,RiverGeometry.project(key,point:line[0],latitude:35.696,longitude:139.814))
        XCTAssertEqual(mesh.last,RiverGeometry.project(key,point:line[1],latitude:35.696,longitude:139.814))
    }

    func testRejectsTruncatedOutOfBoundsAndTrailingData() {
        var outside = bytes; outside[19] = 32
        var badCount = bytes; badCount[4] = 127
        var badMagic = bytes; badMagic[0] = 0
        for bad in [Array(bytes.dropLast()),bytes+[0],outside,badCount,badMagic] {
            XCTAssertThrowsError(try RiverGeometry.decode(Data(bad)))
        }
    }
}

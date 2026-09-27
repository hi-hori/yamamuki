import XCTest
@testable import YamamukiCore

final class WaterGeometryTests: XCTestCase {
    func tile() -> Data {
        var bytes: [UInt8] = [87,65,84,49]
        func integer(_ value: Int, _ count: Int) {
            for i in 0..<count { bytes.append(UInt8((value >> (i*8)) & 255)) }
        }
        integer(1,4); integer(0,4); integer(2,4)
        for ring in [[0,0,4096,0,4096,4096,0,4096],[1024,1024,3072,1024,3072,3072,1024,3072]] {
            integer(4,4)
            for v in ring { integer(v,2) }
        }
        return Data(bytes)
    }
    func testKeepsOuterRingAndIslandHoleSeparate() throws {
        let polygon = try XCTUnwrap(WaterGeometry.decode(tile()).first)
        XCTAssertFalse(polygon.river)
        XCTAssertEqual(polygon.rings.count,2)
        XCTAssertEqual(polygon.rings[1].first,PlanOffset(x:0.25,y:0.25))
    }
    func testRejectsInvalidData() {
        XCTAssertThrowsError(try WaterGeometry.decode(tile().dropLast()))
        var corrupt = tile(); corrupt[21] = 32
        XCTAssertThrowsError(try WaterGeometry.decode(corrupt))
        XCTAssertThrowsError(try WaterGeometry.decode(tile()+Data([0])))
    }
}

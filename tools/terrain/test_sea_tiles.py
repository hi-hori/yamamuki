import struct
import unittest
import numpy as np
from shapely.geometry import Point, Polygon, LineString
from shapely.ops import unary_union
from shapely.affinity import translate
from sea_tiles import sea_polygons, encode


def decode(data):
    assert data[:4] == b'WAT1'
    count = struct.unpack_from('<I', data, 4)[0]
    offset = 8
    areas = []
    for _ in range(count):
        kind, rings = struct.unpack_from('<II', data, offset); offset += 8
        assert kind == 1
        points = []
        for _ in range(rings):
            size = struct.unpack_from('<I', data, offset)[0]; offset += 4
            assert size >= 3
            ring = [struct.unpack_from('<HH', data, offset + i*4) for i in range(size)]
            assert all(0<=x<=4096 and 0<=y<=4096 for x,y in ring)
            offset += size*4
            points.append([(x/4096,y/4096) for x,y in ring])
        areas.append(Polygon(points[0], points[1:]))
    assert offset == len(data)
    return areas


class SeaTilesTest(unittest.TestCase):
    def test_land_and_sea(self):
        self.assertEqual(decode(encode(sea_polygons(np.ones((272,272))))), [])
        area = decode(encode(sea_polygons(np.full((272,272), np.nan))))
        self.assertAlmostEqual(area[0].area, 1)

    def test_island_hole_and_coast(self):
        field = np.full((272,272), np.nan)
        field[:,:80] = 100
        field[100:140,130:170] = 500
        sea = unary_union(decode(encode(sea_polygons(field))))
        self.assertTrue(sea.is_valid)
        self.assertFalse(sea.covers(Point(.1,.5)))
        self.assertFalse(sea.covers(Point((150.5-8)/256,(120.5-8)/256)))
        self.assertTrue(sea.covers(Point(.8,.5)))
        self.assertEqual(sum(len(p.interiors) for p in sea_polygons(field)), 1)

    def test_adjacent_tile_crossings(self):
        def tile(x):
            yy,xx = np.indices((272,272))
            return np.where(yy-8 > (xx-8+x*256)*.2+60, np.nan, 10.)
        left = unary_union(decode(encode(sea_polygons(tile(0)))))
        right = translate(unary_union(decode(encode(sea_polygons(tile(1))))), xoff=1)
        edge = LineString([(1,0),(1,1)])
        self.assertLessEqual(left.intersection(edge).hausdorff_distance(right.intersection(edge)), 1/4096)


if __name__ == '__main__': unittest.main()

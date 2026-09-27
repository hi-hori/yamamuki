import struct
import unittest
from shapely.geometry import Polygon, Point, mapping
from shapely.ops import unary_union
import water_polygons as w


class WaterPolygonsTest(unittest.TestCase):
    def member(self, ref, role, points):
        return dict(type='way',ref=ref,role=role,geometry=[dict(lon=x,lat=y) for x,y in points])

    def relation(self, members):
        return dict(type='relation',id=1,tags={'natural':'water','water':'lake'},members=members)

    def test_assembles_split_outline_and_keeps_island_hole(self):
        data=self.relation([self.member(1,'outer',[(0,0),(4,0),(4,4)]),
            self.member(2,'outer',[(0,0),(0,4),(4,4)]),
            self.member(3,'inner',[(1,1),(2,1),(2,2),(1,2),(1,1)])])
        features,skipped=w.assemble([data])
        self.assertFalse(skipped)
        geometry=w.shape(features[0]['geometry'])
        self.assertTrue(geometry.covers(Point(3,3)))
        self.assertFalse(geometry.covers(Point(1.5,1.5)))
        self.assertEqual(len(geometry.interiors),1)

    def test_unclosed_outline_is_reported_instead_of_filled(self):
        features,skipped=w.assemble([self.relation([self.member(1,'outer',[(0,0),(4,0),(4,4)])])])
        self.assertFalse(features)
        self.assertEqual(len(skipped),1)

    def test_water_inside_an_island_is_retained(self):
        data=self.relation([self.member(1,'outer',[(0,0),(6,0),(6,6),(0,6),(0,0)]),
            self.member(2,'inner',[(1,1),(5,1),(5,5),(1,5),(1,1)]),
            self.member(3,'outer',[(2,2),(3,2),(3,3),(2,3),(2,2)])])
        features,skipped=w.assemble([data])
        self.assertFalse(skipped)
        geometry=unary_union([w.shape(f['geometry']) for f in features])
        self.assertFalse(geometry.covers(Point(1.5,1.5)))
        self.assertTrue(geometry.covers(Point(2.5,2.5)))

    def test_encoded_tiles_preserve_holes_and_merge_overlaps(self):
        def ring(points): return [w.b.lonlat(3590+x,1615+y,12) for x,y in points]
        outer=ring([(.1,.1),(.9,.1),(.9,.9),(.1,.9),(.1,.1)])
        hole=ring([(.3,.3),(.7,.3),(.7,.7),(.3,.7),(.3,.3)])
        feature=dict(properties={'kind':0},geometry=mapping(Polygon(outer,[hole])))
        # Duplicate source features must not cancel each other under even-odd fill.
        data=dict(w.tiles([feature,feature]))['water/12/3590/1615.wat']
        self.assertEqual(data[:4],b'WAT1')
        count=struct.unpack_from('<I',data,4)[0]; offset=8; parts=[]
        for _ in range(count):
            kind,rings=struct.unpack_from('<II',data,offset); offset+=8
            self.assertEqual(kind,0); coordinates=[]
            for _ in range(rings):
                n=struct.unpack_from('<I',data,offset)[0]; offset+=4
                coordinates.append([struct.unpack_from('<HH',data,offset+4*i) for i in range(n)])
                offset+=n*4
            parts.append(Polygon(coordinates[0],coordinates[1:]))
        self.assertEqual(offset,len(data))
        self.assertEqual(count,1)
        self.assertTrue(parts[0].covers(Point(.2*4096,.2*4096)))
        self.assertFalse(parts[0].covers(Point(.5*4096,.5*4096)))

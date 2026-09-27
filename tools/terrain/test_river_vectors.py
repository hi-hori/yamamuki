import struct
import unittest
import gzip
import json
import tempfile
import zipfile
from pathlib import Path
import river_vectors as r


class RiverVectorsTest(unittest.TestCase):
    def feature(self, name, points):
        return {'properties': {'name': name}, 'geometry': {'coordinates': points}}

    def test_connected_short_sections_remain_visible_as_one_long_river(self):
        features=[self.feature('Main',[(139+i*.1,35),(139+(i+1)*.1,35)]) for i in range(3)]
        # Direction does not affect joining.
        features[1]['geometry']['coordinates'].reverse()
        lengths=r.component_lengths(features)
        self.assertGreater(min(lengths),20)
        self.assertEqual(len(set(lengths)),1)
        self.assertTrue(any(name.startswith('rivers/10/') for name,_ in r.tiles(features)))

    def test_same_name_in_disconnected_places_is_not_merged(self):
        features=[self.feature('Common',[(139+i,35),(139+i+.1,35)]) for i in range(3)]
        self.assertLess(max(r.component_lengths(features)),20)
        self.assertFalse(any(name.startswith('rivers/10/') for name,_ in r.tiles(features)))

    def test_short_tributary_returns_only_at_detail_zoom(self):
        features=[self.feature('Small',[(139,35),(139.01,35)])]
        names=[name for name,_ in r.tiles(features)]
        self.assertTrue(names)
        self.assertTrue(all(name.startswith('rivers/12/') for name in names))

    def test_vector_only_rebuild_preserves_hillshade_and_is_repeatable(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); base=root/'base.zip'; first=root/'first.zip'; second=root/'second.zip'
            feature=self.feature('Small',[(139,35),(139.01,35)])
            with zipfile.ZipFile(base,'w') as archive:
                archive.writestr('manifest.json',json.dumps({'terrain':{'rivers_baked_in':False},
                    'shaded_builder_sha256':'original image provenance'}))
                archive.writestr('terrain/12/3629/1622.webp',b'original image bytes')
                archive.writestr('rivers.geojson.gz',gzip.compress(json.dumps({'features':[feature]}).encode(),mtime=0))
            base.with_suffix('.sha256').write_text(r.b.sha(base.read_bytes()))
            r.rebuild(base,first); r.rebuild(first,second)
            self.assertEqual(first.read_bytes(),second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(archive.read('terrain/12/3629/1622.webp'),b'original image bytes')
                self.assertEqual(json.loads(archive.read('manifest.json'))['shaded_builder_sha256'],'original image provenance')

    def test_crossing_segment_is_kept_even_when_both_ends_are_outside(self):
        self.assertEqual(list(r.clipped_lines([(-10,100),(520,100)])),
                         [[(0,800),(4096,800)]])

    def test_exit_and_reentry_do_not_create_a_false_connection(self):
        lines=list(r.clipped_lines([(100,100),(600,100),(600,400),(100,400)]))
        self.assertEqual(lines,[[(800,800),(4096,800)],[(4096,3200),(800,3200)]])

    def test_adjacent_tiles_share_quantized_intersections(self):
        line=[(100,75),(700,390)]
        left=list(r.clipped_lines(line))
        right=list(r.clipped_lines([(x-512,y) for x,y in line]))
        self.assertEqual(left[-1][-1][1],right[0][0][1])

    def test_simplification_keeps_bends_and_endpoints(self):
        self.assertEqual(r.simplify([(0,0),(1,.1),(2,0),(3,2)]),[(0,0),(2,0),(3,2)])

    def test_binary_tiles_match_coordinates_and_have_no_empty_lines(self):
        features=[{'geometry':{'coordinates':[[139.81,35.69],[139.82,35.7]]}}]
        for name,data in r.tiles(features):
            self.assertEqual(data[:4],b'RIV1')
            offset=8
            for _ in range(struct.unpack_from('<I',data,4)[0]):
                count=struct.unpack_from('<I',data,offset)[0]; offset+=4
                self.assertGreaterEqual(count,2)
                for _ in range(count):
                    x,y=struct.unpack_from('<HH',data,offset); offset+=4
                    self.assertTrue(0<=x<=4096 and 0<=y<=4096)
            self.assertEqual(offset,len(data))
            self.assertTrue(name.endswith('.riv'))

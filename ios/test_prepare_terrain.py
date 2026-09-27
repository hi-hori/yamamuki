import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('prepare_terrain',Path(__file__).with_name('prepare-terrain.py'))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareTerrainTest(unittest.TestCase):
    def source(self,root):
        path=root/'terrain.zip'
        with zipfile.ZipFile(path,'w') as archive:
            archive.writestr('manifest.json',json.dumps({'terrain':{'encoding':'hillshade-webp',
                'tile_size':512,'min_zoom':10,'max_zoom':12,'tile_count':1}}))
            archive.writestr('terrain/12/3626/1617.webp',b'fixture')
            archive.writestr('terrain/../../outside.webp',b'not a valid tile entry')
            for n in ('NOTICE.txt','ODbL-1.0.txt','rivers.geojson.gz'): archive.writestr(n,b'license/source')
        path.with_suffix('.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest())
        return path

    def test_extracts_terrain_and_licenses_without_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source=self.source(root)
            module.prepare(source,root/'output')
            self.assertEqual((root/'output/12/3626/1617.webp').read_bytes(),b'fixture')
            self.assertFalse((root/'outside.webp').exists())
            self.assertTrue((root/'output/ODbL-1.0.txt').exists())
            self.assertTrue((root/'output/rivers.geojson.gz').exists())

    def test_rejects_wrong_hash_before_creating_resources(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source=self.source(root)
            source.with_suffix('.sha256').write_text('0'*64)
            with self.assertRaises(ValueError): module.prepare(source,root/'output')
            self.assertFalse((root/'output').exists())

    def test_rebuild_removes_obsolete_tiles_but_keeps_unrelated_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source=self.source(root); output=root/'output'
            old=output/'12/1/1.webp'; old.parent.mkdir(parents=True); old.write_bytes(b'old')
            (output/'keep.txt').write_text('keep')
            module.prepare(source,output)
            self.assertFalse(old.exists())
            self.assertEqual((output/'keep.txt').read_text(),'keep')

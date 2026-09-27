import csv
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('prepare_peaks', Path(__file__).with_name('prepare-peaks.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PreparePeaksTests(unittest.TestCase):
    def pack(self, path, rows, count=None):
        csv_text = io.StringIO(newline='')
        writer = csv.writer(csv_text)
        writer.writerow(['osm_id', 'latitude', 'longitude', 'name', 'elevation_m'])
        writer.writerows(rows)
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('peaks.csv.gz', gzip.compress(csv_text.getvalue().encode()))
            z.writestr('manifest.json', json.dumps(dict(format_version=1,
                peaks=dict(count=len(rows) if count is None else count, osm_timestamp='2026-09-27'),
                input_last_fetched='2026-09-27')))
            z.writestr('NOTICE.txt', 'notice')
            z.writestr('ODbL-1.0.txt', 'license')
            z.writestr('../escape.txt', 'never extracted')
        path.with_suffix('.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest())

    def test_preserves_names_missing_elevation_and_export_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source, output = root / 'peaks.zip', root / 'output'
            self.pack(source, [[1, 35, 139, '山,"峰"\n北', '']])
            module.prepare(source, output)
            peaks = json.loads((output / 'peaks.json').read_bytes())
            self.assertEqual(peaks[0]['name'], '山,"峰"\n北')
            self.assertIsNone(peaks[0]['elevationM'])
            self.assertEqual(source.read_bytes(), (output / 'peaks.zip').read_bytes())
            self.assertFalse((root / 'escape.txt').exists())
            info = json.loads((output / 'info.json').read_bytes())
            self.assertEqual(info['peaksSHA256'], hashlib.sha256((output / 'peaks.json').read_bytes()).hexdigest())

    def test_rejects_invalid_pack_before_writing(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source, output = root / 'peaks.zip', root / 'output'
            row = [1, 35, 139, '山', 1]
            for rows, count in [([row, row], 2), ([row], 2), ([[1, 'NaN', 139, '山', 1]], 1)]:
                self.pack(source, rows, count)
                with self.assertRaises(ValueError):
                    module.prepare(source, output)
                self.assertFalse(output.exists())
            self.pack(source, [row])
            source.with_suffix('.sha256').write_text('0' * 64)
            with self.assertRaises(ValueError):
                module.prepare(source, output)
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()

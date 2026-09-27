import json
from pathlib import Path
import tempfile
import unittest

from build_peaks import Inputs, elevation, sha


class BuilderTests(unittest.TestCase):
    def test_elevation_units_and_missing_values(self):
        self.assertEqual(elevation('1,234 m'), '1234')
        self.assertEqual(elevation('100 ft'), '30.48')
        self.assertEqual(elevation('12;13'), '12')
        self.assertEqual(elevation('unknown'), '')

    def test_offline_cache_rejects_missing_corrupt_and_wrong_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = Inputs(root, offline=True)
            with self.assertRaises(FileNotFoundError):
                inputs.get('peaks.json', 'https://example.invalid/peaks')
            data = b'{"elements": []}'
            (root / 'peaks.json').write_bytes(data)
            (root / 'peaks.json.json').write_text(json.dumps(dict(
                url='https://example.invalid/peaks', status=200, sha256=sha(data))), encoding='utf-8')
            self.assertEqual(inputs.get('peaks.json', 'https://example.invalid/peaks'), data)
            with self.assertRaises(ValueError):
                inputs.get('peaks.json', 'https://example.invalid/other')
            (root / 'peaks.json').write_bytes(b'corrupt')
            with self.assertRaises(ValueError):
                inputs.get('peaks.json', 'https://example.invalid/peaks')


if __name__ == '__main__':
    unittest.main()

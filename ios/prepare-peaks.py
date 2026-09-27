"""Prepare iOS resources from the verified Android peak-only pack (no network)."""
import argparse
import csv
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def prepare(source, output):
    packed = source.read_bytes()
    expected = source.with_suffix('.sha256').read_text().strip()
    if hashlib.sha256(packed).hexdigest() != expected:
        raise ValueError('Peak pack SHA-256 mismatch')
    with zipfile.ZipFile(io.BytesIO(packed)) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        if manifest['format_version'] != 1:
            raise ValueError('Unsupported pack version')
        rows = csv.DictReader(io.StringIO(gzip.decompress(archive.read('peaks.csv.gz')).decode('utf-8'), newline=''))
        if rows.fieldnames != ['osm_id', 'latitude', 'longitude', 'name', 'elevation_m']:
            raise ValueError('Invalid CSV header')
        peaks, seen = [], set()
        for row in rows:
            peak = dict(osmId=int(row['osm_id']), name=row['name'], latitude=float(row['latitude']),
                        longitude=float(row['longitude']), elevationM=float(row['elevation_m']) if row['elevation_m'] else None)
            if (peak['osmId'] in seen or not peak['name'].strip() or
                not -90 <= peak['latitude'] <= 90 or not -180 <= peak['longitude'] <= 180 or
                (peak['elevationM'] is not None and not math.isfinite(peak['elevationM']))):
                raise ValueError('Invalid or duplicate peak')
            seen.add(peak['osmId'])
            peaks.append(peak)
        if len(peaks) != manifest['peaks']['count']:
            raise ValueError('Peak count mismatch')
        files = {name: archive.read(name) for name in ['NOTICE.txt', 'ODbL-1.0.txt']}
    files['peaks.json'] = json.dumps(peaks, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode()
    files['info.json'] = json.dumps(dict(count=len(peaks), osmDate=manifest['peaks']['osm_timestamp'][:10],
        inputDate=manifest['input_last_fetched'][:10], bytes=len(packed), sourceSHA256=expected,
        peaksSHA256=hashlib.sha256(files['peaks.json']).hexdigest())).encode()
    files['peaks.zip'] = packed  # Identical redistributable CSV and license, available in Settings.
    output.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        (output / name).write_bytes(data)
    print(f'Prepared {len(peaks)} peaks in {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'app/src/main/assets/offline/peaks.zip')
    parser.add_argument('--output', type=Path, default=ROOT / 'ios/Generated/Peaks')
    args = parser.parse_args()
    prepare(args.source, args.output)
